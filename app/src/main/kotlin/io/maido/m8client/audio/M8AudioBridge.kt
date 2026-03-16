package io.maido.m8client.audio

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

/**
 * M8AudioBridge — Replaces the broken libusb isochronous audio path with
 * Android's native AudioRecord → AudioTrack loopback.
 *
 * WHY THIS WORKS:
 * Android's kernel already includes the snd-usb-audio driver. When the M8/Teensy
 * is connected via OTG, the kernel recognizes it as a USB Audio Class device and
 * creates ALSA card entries. The kernel handles isochronous USB transfers internally.
 *
 * The current m8c-android tries to bypass the kernel entirely using libusb
 * isochronous transfers from userspace. This fails on many devices with:
 *   "libusb: error [submit_iso_transfer] submiturb failed, errno=95 (EOPNOTSUPP)"
 *
 * This bridge uses AudioRecord (reading from the USB audio INPUT) and pipes it
 * directly to AudioTrack (the phone's speaker/output). The kernel handles all
 * the USB isochronous complexity.
 */
class M8AudioBridge(private val context: Context) {

    companion object {
        private const val TAG = "M8AudioBridge"

        // M8 audio specs: 16-bit stereo PCM at 44100Hz
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Buffer sizing: ~10ms worth of audio for low latency
        // 44100 samples/sec * 2 channels * 2 bytes/sample * 0.01 sec = 1764 bytes
        // Round up to power of 2 for efficiency
        private const val PREFERRED_BUFFER_SIZE = 2048
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var bridgeJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    /**
     * Find the USB audio input device (the M8/Teensy).
     * Returns the AudioDeviceInfo for the USB audio source, or null if not found.
     */
    private fun findUsbAudioInputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // Prefer USB_DEVICE type, fall back to USB_HEADSET
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
    }

    /**
     * Find the best output device — prefer the phone speaker, avoid routing
     * back to the USB device (which is the M8 itself).
     */
    private fun findOutputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Priority: Bluetooth > Wired headset > Built-in speaker
        // NEVER route to USB output (that's the M8 itself, causes feedback loop)
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /**
     * Start the audio bridge. Returns true if successfully started.
     */
    fun start(): Boolean {
        if (isRunning) {
            Log.w(TAG, "Audio bridge already running")
            return true
        }

        val usbInput = findUsbAudioInputDevice()
        if (usbInput == null) {
            Log.e(TAG, "No USB audio input device found. Is the M8/Teensy connected?")
            return false
        }
        Log.i(TAG, "Found USB audio input: ${usbInput.productName} (ID: ${usbInput.id})")

        val outputDevice = findOutputDevice()
        Log.i(TAG, "Output device: ${outputDevice?.productName ?: "system default"}")

        // Calculate minimum buffer sizes
        val minRecordBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT
        )
        val minTrackBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT
        )

        if (minRecordBufferSize == AudioRecord.ERROR_BAD_VALUE ||
            minTrackBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Audio format not supported by device")
            return false
        }

        // Use at least 2x minimum buffer for stability
        val recordBufferSize = maxOf(minRecordBufferSize * 2, PREFERRED_BUFFER_SIZE * 4)
        val trackBufferSize = maxOf(minTrackBufferSize * 2, PREFERRED_BUFFER_SIZE * 4)

        try {
            // Create AudioRecord targeting the USB input device
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED) // Raw audio, no AGC/NS
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_IN)
                            .build()
                    )
                    .setBufferSizeInBytes(recordBufferSize)
                    .build().also { record ->
                        // Route to the USB device explicitly
                        record.setPreferredDevice(usbInput)
                    }
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    recordBufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize. State: ${audioRecord?.state}")

                // Fallback: try with DEFAULT source (some devices don't support UNPROCESSED)
                Log.i(TAG, "Retrying with DEFAULT audio source...")
                audioRecord?.release()
                audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG_IN)
                                .build()
                        )
                        .setBufferSizeInBytes(recordBufferSize)
                        .build().also { record ->
                            record.setPreferredDevice(usbInput)
                        }
                } else {
                    @Suppress("DEPRECATION")
                    AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG_IN,
                        AUDIO_FORMAT,
                        recordBufferSize
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord still failed after fallback. Device may not support USB audio capture.")
                    audioRecord?.release()
                    audioRecord = null
                    return false
                }
            }

            // Create AudioTrack for output
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build().also { track ->
                        // Route output to speaker, NOT back to USB
                        if (outputDevice != null) {
                            track.setPreferredDevice(outputDevice)
                        }
                    }
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    trackBufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                cleanup()
                return false
            }

            // Start the bridge loop
            isRunning = true
            audioRecord?.startRecording()
            audioTrack?.play()

            bridgeJob = scope.launch {
                runBridgeLoop()
            }

            Log.i(TAG, "Audio bridge started successfully!")
            Log.i(TAG, "  Record buffer: $recordBufferSize bytes")
            Log.i(TAG, "  Track buffer: $trackBufferSize bytes")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording. Add RECORD_AUDIO permission.", e)
            cleanup()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio bridge", e)
            cleanup()
            return false
        }
    }

    /**
     * The core audio bridge loop — reads PCM from USB input and writes to speaker output.
     * Runs on a dedicated coroutine with IO dispatcher.
     */
    private suspend fun runBridgeLoop() {
        // Use a ShortArray for 16-bit PCM (more efficient than byte array)
        val buffer = ShortArray(PREFERRED_BUFFER_SIZE)

        // Set thread priority for audio
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        Log.d(TAG, "Bridge loop started on thread: ${Thread.currentThread().name}")

        var underrunCount = 0L
        var totalFrames = 0L

        while (isRunning && isActive) {
            val record = audioRecord ?: break
            val track = audioTrack ?: break

            try {
                // Read from USB audio input
                val shortsRead = record.read(buffer, 0, buffer.size)

                when {
                    shortsRead > 0 -> {
                        // Write to speaker output
                        val shortsWritten = track.write(buffer, 0, shortsRead)
                        if (shortsWritten < 0) {
                            Log.w(TAG, "AudioTrack write error: $shortsWritten")
                        } else if (shortsWritten < shortsRead) {
                            underrunCount++
                            if (underrunCount % 100 == 0L) {
                                Log.w(TAG, "Buffer underrun count: $underrunCount")
                            }
                        }
                        totalFrames += shortsRead
                    }
                    shortsRead == 0 -> {
                        // No data available, yield briefly
                        delay(1)
                    }
                    shortsRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord not initialized properly")
                        break
                    }
                    shortsRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord bad parameter")
                        break
                    }
                    shortsRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.e(TAG, "AudioRecord dead object — USB device disconnected?")
                        break
                    }
                    else -> {
                        Log.w(TAG, "AudioRecord unexpected result: $shortsRead")
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in bridge loop", e)
                break
            }
        }

        Log.i(TAG, "Bridge loop ended. Total frames: $totalFrames, underruns: $underrunCount")
    }

    /**
     * Stop the audio bridge and release resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping audio bridge...")
        isRunning = false

        bridgeJob?.cancel()
        bridgeJob = null

        cleanup()
        Log.i(TAG, "Audio bridge stopped.")
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) { /* ignore */ }
        try {
            audioRecord?.release()
        } catch (e: Exception) { /* ignore */ }
        audioRecord = null

        try {
            audioTrack?.stop()
        } catch (e: Exception) { /* ignore */ }
        try {
            audioTrack?.release()
        } catch (e: Exception) { /* ignore */ }
        audioTrack = null
    }

    /**
     * Check if the audio bridge is currently running.
     */
    fun isActive(): Boolean = isRunning

    /**
     * Get diagnostic info about available audio devices.
     * Useful for debugging.
     */
    fun getDiagnostics(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val sb = StringBuilder()
        sb.appendLine("=== M8 Audio Bridge Diagnostics ===")
        sb.appendLine()
        sb.appendLine("INPUT DEVICES:")
        inputs.forEach { device ->
            sb.appendLine("  [${device.id}] ${device.productName} — type=${deviceTypeName(device.type)}")
            sb.appendLine("       sample rates: ${device.sampleRates.joinToString()}")
            sb.appendLine("       channels: ${device.channelCounts.joinToString()}")
            sb.appendLine("       encodings: ${device.encodings.joinToString()}")
        }
        sb.appendLine()
        sb.appendLine("OUTPUT DEVICES:")
        outputs.forEach { device ->
            sb.appendLine("  [${device.id}] ${device.productName} — type=${deviceTypeName(device.type)}")
        }
        sb.appendLine()

        val usbInput = findUsbAudioInputDevice()
        sb.appendLine("USB Audio Input Found: ${usbInput != null}")
        if (usbInput != null) {
            sb.appendLine("  Name: ${usbInput.productName}")
            sb.appendLine("  ID: ${usbInput.id}")
        }
        sb.appendLine("Bridge Running: $isRunning")
        sb.appendLine("===================================")

        return sb.toString()
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        else -> "TYPE_$type"
    }
}
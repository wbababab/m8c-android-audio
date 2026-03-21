package io.maido.m8client

import android.Manifest
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.getBroadcast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.maido.m8client.M8Key.*
import io.maido.m8client.M8Util.isM8
import io.maido.m8client.settings.GeneralSettings
import org.libsdl.app.SDLActivity
import io.maido.m8client.audio.M8AudioBridge


class M8SDLActivity : SDLActivity() {

    companion object {
        private const val TAG = "M8SDLActivity"
        private const val ACTION_USB_PERMISSION = "io.maido.m8client.USB_PERMISSION"
        private const val RECORD_AUDIO_PERMISSION_REQUEST = 1001

        fun startM8SDLActivity(context: Context) {
            val sdlActivity = Intent(context, M8SDLActivity::class.java)
            context.startActivity(sdlActivity)
        }
    }

    /**
     * The NDK build names the SDL3 library "SDL2" (LOCAL_MODULE := SDL2 in SDL/Android.mk).
     * We must load it as "SDL2", then our app libraries.
     * SDL_main is defined in libm8c.so (main.c compiled there), so m8c must come before main.
     * libusb-1.0 is auto-loaded as a transitive dependency.
     */
    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "m8c", "main")
    }

    /**
     * SDL_main (the SDL_EnterAppMainCallbacks wrapper) is compiled into libm8c.so
     * because main.c lives in the m8c NDK module.  Point nativeRunMain at that library.
     */
    override fun getMainSharedObject(): String {
        return "${applicationInfo.nativeLibraryDir}/libm8c.so"
    }

    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device = getExtraDevice(intent)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null && isM8(device)) {
                            connectToM8(device)
                        } else {
                            Log.d(TAG, "Device was not M8")
                        }
                    } else {
                        Log.d(TAG, "Permission denied for device $device")
                    }
                }
            } else if (ACTION_USB_DEVICE_DETACHED == action) {
                Log.d(TAG, "Device was detached!")
                audioBridge.stop()
            }
        }
    }

    @Suppress("Deprecation")
    private fun getExtraDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private var usbConnection: UsbDeviceConnection? = null
    private lateinit var audioBridge: M8AudioBridge

    override fun onStart() {
        Log.i(TAG, "Searching for an M8 device")
        super.onStart()
        val usbManager = getSystemService(UsbManager::class.java)
        for (device in usbManager.deviceList.values) {
            if (isM8(device)) {
                connectToM8WithPermission(usbManager, device)
                break
            }
        }
    }

    override fun onResume() {
        Log.d(TAG, "onResume()")
        super.onResume()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        audioBridge.stop()
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        usbConnection?.close()
    }

    override fun onStop() {
        Log.d(TAG, "onStop()")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        audioBridge = M8AudioBridge(this)

        val generalPreferences = GeneralSettings.getGeneralPreferences(this)
        hintAudioDriver(generalPreferences.audioDriver)
        lockOrientation(
            if (generalPreferences.useNewLayout) "Portrait PortraitUpsideDown"
            else if (generalPreferences.lockOrientation) "LandscapeLeft LandscapeRight" else null
        )
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_DEVICE_DETACHED))

        // Request RECORD_AUDIO early so permission is already granted when the M8
        // connects. If we wait until connectToM8(), the permission dialog fires
        // mid-connect, after openDevice() may have detached the snd-usb-audio
        // kernel driver, making the USB audio device invisible to AudioRecord.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_REQUEST
            )
        }
    }

    private fun connectToM8(device: UsbDevice) {
        // Snapshot USB audio device info BEFORE opening the USB device.
        // openDevice() can detach the kernel snd-usb-audio driver on some devices,
        // making the M8's audio interface invisible to AudioManager afterwards.
        audioBridge.snapshotUsbAudioDevice()

        // Start AudioRecord→AudioTrack bridge BEFORE openDevice(), so the recording
        // stream is established while snd-usb-audio may still be bound to the audio
        // interfaces. On devices without snd-usb-audio the bridge will self-abort.
        startAudioBridge()

        val usbManager = getSystemService(UsbManager::class.java)!!
        usbConnection = usbManager.openDevice(device)?.also {
            Log.d(TAG, "Setting file descriptor to ${it.fileDescriptor} ")
            connect(it.fileDescriptor)
        }
    }

    /**
     * Start the native AudioRecord→AudioTrack audio bridge.
     * This bypasses the broken libusb isochronous path and uses Android's
     * kernel USB audio driver instead.
     */
    private fun startAudioBridge() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Requesting RECORD_AUDIO permission for audio bridge")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_REQUEST
            )
            return
        }
        launchAudioBridge()
    }

    private fun launchAudioBridge() {
        Log.d(TAG, audioBridge.getDiagnostics())
        val prefs = GeneralSettings.getGeneralPreferences(this)
        Log.i(TAG, "Starting audio bridge with output device ID=${prefs.audioDevice}")
        val success = audioBridge.start(outputDeviceId = prefs.audioDevice)
        if (success) {
            Log.i(TAG, "Audio bridge started successfully")
        } else {
            Log.e(TAG, "Audio bridge failed to start — check logcat for diagnostics")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "RECORD_AUDIO permission granted, starting audio bridge")
                launchAudioBridge()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied — audio will not work")
            }
        }
    }

    private fun requestM8Permission(usbManager: UsbManager, usbDevice: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION)
        val permissionIntent = getBroadcast(this, 0, intent, FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                usbReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                usbReceiver,
                IntentFilter(ACTION_USB_PERMISSION)
            )
        }
        usbManager.requestPermission(usbDevice, permissionIntent)
    }

    private fun connectToM8WithPermission(usbManager: UsbManager, usbDevice: UsbDevice) {
        if (usbManager.hasPermission(usbDevice)) {
            Log.i(TAG, "Permission granted!")
            connectToM8(usbDevice)
        } else {
            Log.i(TAG, "Requesting USB device permission")
            requestM8Permission(usbManager, usbDevice)
        }
    }

    override fun onUnhandledMessage(command: Int, param: Any?): Boolean {
        if (command == 0x8001 && param is Int) {
            val r = param shr 16
            val g = (param shr 8) and 0xFF
            val b = param and 0xFF
            Log.d(TAG, "Background color changed to $r $g $b")
            val main = findViewById<ViewGroup>(R.id.main)
            main.setBackgroundColor(Color.rgb(r, g, b))
            return true
        }
        return super.onUnhandledMessage(command, param)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.d(TAG, "Orientation to portrait, show alternative buttons")
            findViewById<View>(R.id.leftButtonsAlt)?.visibility = View.VISIBLE
            findViewById<View>(R.id.rightButtonsAlt)?.visibility = View.VISIBLE
        } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "Orientation to portrait, hide alternative buttons")
            findViewById<View>(R.id.leftButtonsAlt)?.visibility = View.GONE
            findViewById<View>(R.id.rightButtonsAlt)?.visibility = View.GONE
        }
        Handler(Looper.getMainLooper()).postDelayed({
            M8TouchListener.resetScreen();
        }, 100)
        super.onConfigurationChanged(newConfig)
    }

    override fun setContentView(view: View) {
        val mainLayout = FrameLayout(this)
        val m8Layout = layoutInflater.inflate(R.layout.m8, mainLayout, true)
        val generalPreferences = GeneralSettings.getGeneralPreferences(this)
        if (generalPreferences.showButtons) {
            val layout =
                if (generalPreferences.useNewLayout) R.layout.buttons_alt else R.layout.buttons
            val buttons = layoutInflater.inflate(layout, mainLayout, false)
            setButtonListeners(buttons)
            mainLayout.addView(buttons)
        } else {
            setButtonListeners(m8Layout)
        }
        val screen = mainLayout.findViewById<ViewGroup>(R.id.screen)
        screen.addView(view)

        // Small overlay button to toggle the SDL debug log (always accessible).
        val logBtn = Button(this).apply {
            text = "LOG"
            textSize = 9f
            alpha = 0.55f
            setOnClickListener {
                toggleDebugOverlay()
                Toast.makeText(this@M8SDLActivity, "Debug overlay toggled", Toast.LENGTH_SHORT).show()
            }
        }
        mainLayout.addView(logBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).also { it.topMargin = 4; it.marginEnd = 4 })

        super.setContentView(mainLayout)
    }

    private fun setButtonListeners(buttons: View) {
        mapOf(
            R.id.up to UP,
            R.id.upAlt to UP,
            R.id.down to DOWN,
            R.id.downAlt to DOWN,
            R.id.left to LEFT,
            R.id.leftAlt to LEFT,
            R.id.right to RIGHT,
            R.id.rightAlt to RIGHT,
            R.id.play to PLAY,
            R.id.playAlt to PLAY,
            R.id.shift to SHIFT,
            R.id.shiftAlt to SHIFT,
            R.id.option to OPTION,
            R.id.optionAlt to OPTION,
            R.id.edit to EDIT,
            R.id.editAlt to EDIT,
        )
            .forEach { (id, key) -> setListener(buttons, id, key) }
    }

    private fun setListener(buttons: View, viewId: Int, key: M8Key) {
        buttons.findViewById<View>(viewId)?.setOnTouchListener(M8TouchListener(key))
    }

    // SDL's dispatchKeyEvent consumes KEYCODE_BACK (returns true) so onBackPressed() is
    // never reached. Intercept it here before SDL gets it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                startActivity(Intent(this, M8StartActivity::class.java))
                finish()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Debug: volume-up long press toggles the in-app SDL log overlay.
    // This shows all SDL_Log output as an overlay on the M8 display.
    private var volumeUpPressMs = 0L
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpPressMs = System.currentTimeMillis()
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val held = System.currentTimeMillis() - volumeUpPressMs
            if (held >= 1500) {
                // Long press (≥1.5s) on volume-up toggles the debug log overlay
                toggleDebugOverlay()
                Toast.makeText(this, "Debug overlay toggled", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private external fun connect(fileDescriptor: Int)
    private external fun hintAudioDriver(audioDriver: String?)
    private external fun lockOrientation(orientation: String?)
    private external fun toggleDebugOverlay()
    @Suppress("unused")
    private external fun setDebugMode(enabled: Boolean)

}

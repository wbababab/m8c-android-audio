# m8c for Android (with working audio)

Android wrapper for the awesome [m8c](https://github.com/laamaa/m8c), forked from
[v3rm0n/m8c-android](https://github.com/v3rm0n/m8c-android).

This fork adds **working USB audio** on Android. See the [Audio section](#audio) below for the
full technical story.

Nothing gets close to the real thing so get yours from [here](https://dirtywave.com/products/m8-tracker).

## Features

- [x] Display
- [x] Game controller input
- [x] Touch screen input
- [x] Audio playback (via kernel `snd-usb-audio` + `AudioRecord`/`AudioTrack`)

## Building

### Prerequisites

- Android SDK + NDK version `28.2.13676358`

### Usage

```bash
git submodule update --init   # downloads SDL2, libusb, m8c
./gradlew installDebug        # builds and installs to a connected device
```

## Buttons

- Touch left and right at the same time: reset screen
- Touch up and down at the same time: go back to settings

**NB!** When on-screen buttons are hidden, the margins on either side of the screen in landscape
mode still work as buttons.

---

## Audio

### Why audio didn't work in the original project

The M8 (Teensy 4.1) presents a composite USB device with two logical parts:

- **Interfaces 0–1** — CDC serial (display protocol and input)
- **Interfaces 2–4** — USB Audio Class (44.1 kHz 16-bit stereo, isochronous endpoint `0x85`)

The original m8c-android used **libusb isochronous transfers** from userspace to capture audio off
interface 4. This fails on most Android devices because Android's `usbfs` kernel driver does not
support `USBDEVFS_URB_TYPE_ISO` — the ioctl returns `errno=95 (EOPNOTSUPP)`. This is a kernel
limitation, not a libusb bug. It works on a small number of devices whose kernels happen to have
this enabled, which is why audio worked for some users and not others.

Even on devices where the ioctl is supported, there is a second problem: before submitting ISO
transfers, the code must call `libusb_detach_kernel_driver()` to evict Android's `snd-usb-audio`
kernel driver from interface 4. On many Android kernels `USBDEVFS_DISCONNECT` is blocked by SELinux
policy, so this step also fails with `LIBUSB_ERROR_NOT_SUPPORTED`, leaving `snd-usb-audio` holding
the interface and causing `libusb_claim_interface()` to fail with `LIBUSB_ERROR_BUSY`.

The original developer noted: *"Getting audio to work on Android proved to be the most difficult
aspect of the Android port… there is no audio when you connect the device and whatever has been
tried there is no easy way to get it to work."*

### Why the kernel already has what we need

Android's kernel includes the `snd-usb-audio` driver (the same one desktop Linux uses). On devices
where it is compiled in, it automatically binds to the M8's audio interfaces when the device
connects over USB OTG, exposing the M8 as a standard `AudioRecord` input source — no isochronous
URBs required from userspace. The kernel handles all of that internally.

For example, the OnePlus Nord kernel (`android_kernel_oneplus_sm7250`) has:

```
CONFIG_SND_USB_AUDIO=y
CONFIG_SND_USB_AUDIO_QMI=y
```

### What this fork does

**`M8AudioBridge` (Kotlin)** — `app/src/main/kotlin/io/maido/m8client/audio/M8AudioBridge.kt`

- Discovers the M8's USB audio input via `AudioManager.getDevices(GET_DEVICES_INPUTS)`, filtering
  for `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`
- Records from it using `AudioRecord` at 44.1 kHz, 16-bit stereo
- Plays back through `AudioTrack` to the phone speaker or Bluetooth (never back to the USB device)
- Snapshots the `AudioDeviceInfo` handle *before* `UsbManager.openDevice()` is called, because
  `openDevice()` can cause `snd-usb-audio` to detach
- Verifies routing after connect and aborts cleanly if Android fell back to the built-in mic

**The critical fix — `app/jni/m8c/src/main.c`**

The root cause of failure even with `M8AudioBridge` in place was that m8c's SDL main loop was
*still* calling the native `audio_initialize()` function (from `audio_libusb.c`) roughly one second
after the device connected. That function calls `libusb_detach_kernel_driver(devh, 4)` to try to
claim interface 4 for ISO capture. On kernels where `USBDEVFS_DISCONNECT` works, this silently
evicts `snd-usb-audio`, killing the `AudioRecord` stream mid-session. Both audio paths were running
simultaneously and fighting each other, with the libusb path winning the race and leaving no audio
at all.

The fix guards every call to `audio_initialize()` and `audio_close()` in `main.c` with
`#ifndef __ANDROID__`, so the libusb audio path is compiled out entirely on Android:

```c
#ifndef __ANDROID__
    if (ctx->conf.audio_enabled) {
        if (audio_initialize(...) <= 0) { ... }
    }
#endif
```

This leaves `snd-usb-audio` undisturbed on interface 4 for the entire session. libusb only ever
touches interfaces 0 and 1 (CDC serial), which it needs for the display/input protocol.

### Device compatibility

This approach works on any Android device whose kernel has `CONFIG_SND_USB_AUDIO=y`. To check
whether your device meets this requirement, connect the M8 before opening the app and look for
a USB audio input in the diagnostic log, or check your kernel config.

If `AudioManager.getDevices()` returns no USB input device, `M8AudioBridge` will log a warning and
abort gracefully. On those devices audio will not work (this is a kernel limitation and cannot be
fixed in userspace without a custom kernel).

---

### Links

- [Dirtywave M8 Tracker](https://dirtywave.com/products/m8-tracker)
- [m8c](https://github.com/laamaa/m8c)
- [v3rm0n/m8c-android](https://github.com/v3rm0n/m8c-android) (upstream Android port)
- [M8 Headless Firmware](https://github.com/Dirtywave/M8HeadlessFirmware)
- [usbaudio-android-demo](https://github.com/shenki/usbaudio-android-demo)

---

*Audio implementation and diagnosis in this fork was developed with [Claude Code](https://claude.ai/code).*

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

Android wrapper for [m8c](https://github.com/laamaa/m8c), a client for the Dirtywave M8 headless tracker. The app exposes the M8 USB device's display and input via a native SDL2/libusb C layer, bridged to Kotlin via JNI. This fork adds native USB audio capture using Android's AudioRecord/AudioTrack APIs to work around libusb's broken isochronous transfer support on Android.

## Build Commands

Before first build, initialize git submodules (SDL2, libusb, m8c):
```
git submodule update --init
```

Build and install to connected device:
```
./gradlew installDebug
```

Build release APK:
```
./gradlew assembleRelease
```

Run unit tests:
```
./gradlew test
```

**Prerequisites:** Android SDK + NDK. NDK version `28.2.13676358` is expected (set in `app/build.gradle`). Supported ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

## Architecture

### Two-Layer Design

**Kotlin layer** (`app/src/main/kotlin/io/maido/m8client/`):
- `M8StartActivity` — Launcher/settings activity. Manages app configuration, permissions, and launches `M8SDLActivity`.
- `M8SDLActivity` — Extends SDL's `SDLActivity`. Handles USB device attach/detach, starts `M8AudioBridge`, routes gamepad/touch input to native code.
- `M8AudioBridge` — Core audio feature of this fork. Discovers the M8's USB audio input device via Android's `AudioManager` (types `USB_DEVICE`, `USB_HEADSET`, `USB_ACCESSORY`), records at 44.1 kHz 16-bit stereo using `AudioRecord`, and plays back via `AudioTrack` to speaker/Bluetooth. Bypasses libusb entirely for audio.
- `M8TouchListener` — Translates touch events on the on-screen button overlay to JNI calls.
- `M8Configuration` — Reads/writes INI config files (via `ini4j`) for m8c's native configuration.
- `M8Key` — Enum of M8 key codes mapped to SDL keycodes.
- `settings/GeneralSettings`, `settings/GamepadSettings` — SharedPreferences wrappers for UI configuration.

**Native layer** (`app/jni/`):
- `src/handlers.c` — Thin JNI bridge: connects Kotlin events to m8c/SDL native functions (`connect`, `sendClickEvent`, `hintAudioDriver`, `resetScreen`, `lockOrientation`, `exit`).
- `m8c/` — m8c application logic, compiled as a shared library with `-DUSE_LIBUSB`.
- `libusb/` — libusb-1.0 for USB communication (display/input; audio is handled by Android kernel driver instead).
- `SDL/` — SDL2, compiled with AAudio, OpenSL ES, and Android audio drivers.

Native build uses **NDK Build** (`Android.mk` files), not CMake. The `app/build.gradle` passes `APP_PLATFORM=android-26` and `USE_PC_NAME=1` to ndk-build.

### USB Device

Target device identified by USB VID:PID `5824:1162` (0x16C0:0x048A), declared in `app/src/main/res/xml/device_filter.xml`. This triggers `USB_DEVICE_ATTACHED` intent and routes directly to `M8SDLActivity`.

### Audio Design Decision

The key architectural decision in this fork: Android's kernel USB audio driver (`snd-usb-audio`) handles the M8's audio interface, making the device appear as a standard `AudioRecord` input source. `M8AudioBridge` discovers it by iterating `AudioManager.getDevices(GET_DEVICES_INPUTS)` and filtering for USB types. This avoids libusb isochronous transfers which are unreliable/broken on Android.

## Version

Version name is set in `gradle.properties` (`versionName`), not in `build.gradle`.

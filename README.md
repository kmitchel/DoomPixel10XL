# DOOM for Android

An Android port of DOOM using the Chocolate DOOM / doomgeneric engine, a Kotlin touch interface, and native rendering and audio backends. The project is named for the Google Pixel 10 XL; the code targets Android 10 and later on ARM64 and x86-64.

## Features

- A centered 4:3 game surface with touch controls in the sidebars on wide displays.
- A virtual movement stick, swipe turning, fire/use/strafe buttons, weapon selection, menu controls, and automap controls. Stick deflection controls movement speed continuously: small movements walk, full deflection runs, and releasing stops. There is no run toggle.
- Gamepad buttons and analog movement.
- Three rendering modes: the base 640x400 framebuffer, 4x texture enlargement, or texture enlargement plus a 2x frame filter producing 1280x800 output. These are custom palette and edge-directed filters with fixed-strength sharpening, with no machine-learning model.
- Optional font highlighting and shadows when filtering is enabled.
- AAudio stereo output, up to 16 sound-effect channels, and a custom MUS music synthesizer. The synthesizer approximates instruments; it does not emulate an OPL chip.
- Haptic effects with a vibration fallback on devices without the required composition primitives.
- Bundled DOOM shareware data, a base-game IWAD selector/importer, and recovery/export tools for saves quarantined by earlier versions. Add-on PWAD loading is not supported by the importer.
- The engine retains its 35 Hz simulation loop. Rendering and audio performance depend on the device; this repository includes no Pixel-specific benchmark results.

## Building

Use JDK 17 and an Android SDK installation. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir` pointing to the SDK.

The project uses Android Gradle Plugin 8.5.2, Gradle 8.7, SDK 35, NDK 27.0.12077973, and CMake 3.22.1. Gradle may download missing SDK components when the SDK is writable and its licenses are accepted.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Licenses and attribution

Licensed under GPL-2.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for licensing, trademark, and attribution details.

## Host regression checks

On Linux with a C++17 compiler and Bash:

```bash
./tests/native/run.sh
```

This runs MUS parser, analog movement, and menu shortcut regressions with undefined-behavior sanitization. The tests use inert Android/AAudio headers and do not test audio-device behavior or replace an Android build.

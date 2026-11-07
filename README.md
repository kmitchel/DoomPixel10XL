# DOOM (1993) for Google Pixel 10 XL

A high-performance Android port of classic id Software DOOM (1993) optimized specifically for modern flagship hardware like the Google Pixel 10 XL (Tensor G-series).

## Features

- **Strict 4:3 Aspect Ratio:** Maintained on widescreen 20:9 displays with balanced touch controls in the letterboxed sidebars.
- **Native 60 FPS Native Audio Engine:** AAudio low-latency PCM playback running OPL3 FM synth emulation (`mus2mid` + GUS/OPL sound system).
- **Stage 1 WAD Asset Super-Sampling:** Real-time 4× super-sampling of wall, floor, ceiling textures and sprite patches.
- **Stage 2 Edge-Directed Reconstruction & CAS:** Real-time 2× xBR contour reconstruction (640×400 → 1280×800) followed by Contrast-Adaptive Sharpening.
- **Enhanced In-Game Typography:** Specular beveled in-game fonts (`STCFN*`) with translucent drop-shadows and 2-stage xBR contour smoothing.
- **Tactile On-Screen Controls:**
  - Responsive analog virtual stick (Rotate-only) with smart dual-zone origin and dynamic leash.
  - Dedicated flanking strafe buttons (`◀ STRF` / `STRF ▶`) on either side of `FIRE`.
  - Context-aware top utility buttons (`MENU`, `ENTER`, `BACK`, `MAP`, `Y/N`).
  - Automap navigation controls (`Z+`, `Z-`, `FLW`, `GRID`, `MRK`, `CLR`).
  - In-game Cheats & Level Warp modal dialog.
  - Custom `.WAD` file loader and importer.

## Architecture

- **Engine:** Chocolate DOOM / doomgeneric core in C99.
- **Android Layer:** Kotlin + Android NDK (C++17, Clang with `-O3` optimizations).
- **Target SDK:** Android 15 (API 35), Min SDK: Android 10 (API 29), 64-bit ABI (`arm64-v8a`, `x86_64`).

## Building

```bash
gradle assembleDebug
```

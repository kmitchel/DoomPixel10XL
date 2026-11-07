# DOOM (1993) for Google Pixel 10 XL

A high-performance Android port of classic id Software DOOM (1993) optimized specifically for modern flagship hardware like the Google Pixel 10 XL (Tensor G-series).

## Features

- **Strict 4:3 Aspect Ratio:** Maintained on widescreen 20:9 displays with balanced touch controls in the letterboxed sidebars.
- **Ultra-Low-Latency Audio Engine:** AAudio non-blocking real-time PCM playback with OPL2/FM-inspired synth voice synthesis and 16-channel digital SFX mixing.
- **Classic 35 Hz Engine Tickrate with Smooth Android Sync:** Deterministic 35 Hz engine simulation preserving vanilla game physics and demo compatibility.
- **Stage 1 AI Texture Super-Sampling:** Real-time 4× super-sampling of wall and flat textures (floors & ceilings) using edge-directed xBR and perceptual colormap blending.
- **Stage 2 Edge-Directed Reconstruction & CAS:** Real-time 2× xBR contour reconstruction (640×400 → 1280×800) followed by Contrast-Adaptive Sharpening (CAS).
- **Enhanced In-Game Typography:** Specular beveled in-game fonts (`STCFN*`) with translucent drop-shadows and 2-stage xBR contour smoothing.
- **Tactile On-Screen Controls:**
  - Responsive analog virtual stick (Rotate-only) with smart dual-zone origin and dynamic leash.
  - Dedicated flanking strafe buttons (`◀ STRF` / `STRF ▶`) on either side of `FIRE`.
  - Context-aware dynamic utility buttons (`MENU`, `ENTER`, `BACK`, `MAP`, `Y/N`).
  - Automap navigation controls (`Z+`, `Z-`, `FLW`, `GRID`, `MRK`, `CLR`).
  - In-game Cheats & Level Warp modal dialog.
  - In-game Custom `.WAD` file loader, SAF importer, and Recovered Saves Browser.

## Architecture

- **Engine:** Chocolate DOOM / doomgeneric core in C99.
- **Android Layer:** Kotlin + Android NDK (C++17, Clang with `-O3` optimizations).
- **Target SDK:** Android 15 (API 35), Min SDK: Android 10 (API 29), 64-bit ABI (`arm64-v8a`, `x86_64`).

## Building

```bash
./gradlew assembleDebug
```

## Licenses & Attribution

Licensed under the **GNU General Public License version 2 (GPL-2.0)**. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for full licensing, trademark, and open-source attribution details.

<div align="center">

  <img src="assets/icon.png" width="128" height="128" alt="Aero Player Icon" style="border-radius: 28px; box-shadow: 0 8px 24px rgba(147, 51, 234, 0.4);" />

  # Aero Player
  ### Standalone Dual-Engine Android Media Powerhouse

  [![Version](https://img.shields.io/badge/version-v1.4.6-9333EA.svg?style=for-the-badge)](https://github.com)
  [![Package](https://img.shields.io/badge/package-com.aerotech.aeroplayer-7E22CE.svg?style=for-the-badge)](https://github.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?style=for-the-badge&logo=kotlin)](https://kotlinlang.org)
  [![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4.svg?style=for-the-badge&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
  [![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge)](LICENSE)

  <br/>

  > ### 🍁 **Made with 🍁 By Shubh jain**

  <p align="center">
    <b>Aero Player</b> is an ultra-modern, high-performance media player for Android engineered with a <b>standalone dual playback engine architecture</b> (Google Media3 ExoPlayer + Native LibVLC). Featuring fluid glassmorphic UI, custom subtitle rendering, 5-band parametric equalizer, smart folder management, and spring-animated floating pill controls.
  </p>

</div>

---

## 📸 Showcase & Visual Tour

<table align="center" style="border: none; text-align: center;">
  <tr>
    <td width="50%" align="center">
      <b>📁 1. Media Library & Smart Folders</b><br/><br/>
      <img src="assets/screenshot1.png" width="100%" alt="Media Library & Smart Folder Management" style="border-radius: 16px; border: 1px solid #7E22CE;" />
    </td>
    <td width="50%" align="center">
      <b>🚀 2. Standalone Dual-Engine Player</b><br/><br/>
      <img src="assets/screenshot2.png" width="100%" alt="Standalone Dual Engine Player UI" style="border-radius: 16px; border: 1px solid #7E22CE;" />
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <b>💬 3. Unified Subtitle & Typography Engine</b><br/><br/>
      <img src="assets/screenshot3.png" width="100%" alt="Unified Subtitle Engine & Font Styler" style="border-radius: 16px; border: 1px solid #7E22CE;" />
    </td>
    <td width="50%" align="center">
      <b>🎚️ 4. Parametric Audio Suite & Gesture HUD</b><br/><br/>
      <img src="assets/screenshot4.png" width="100%" alt="Parametric Audio Suite & Gesture Controls" style="border-radius: 16px; border: 1px solid #7E22CE;" />
    </td>
  </tr>
</table>

---

## ⚡ Standalone Dual-Engine Architecture

Aero Player integrates two completely independent industry-leading playback cores. You can seamlessly switch between engines mid-stream with sub-second resumption and zero frame buffer stutter:

```
                  ┌────────────────────────────────────────┐
                  │          AERO PLAYER CONTROL           │
                  │   Unified State & Session Coordinator  │
                  └───────────────────┬────────────────────┘
                                      │
              ┌───────────────────────┴───────────────────────┐
              ▼                                               ▼
  ┌─────────────────────────┐                     ┌─────────────────────────┐
  │   Google Media3 Core    │                     │   Native LibVLC 3.6     │
  │     (ExoPlayer 1.4)     │                     │     (ARM64 Native)      │
  ├─────────────────────────┤                     ├─────────────────────────┤
  │ • Zero-overhead HW      │                     │ • Universal codec pack  │
  │ • Jellyfin Dolby AC3    │                     │ • MKV / FLV / WMV / VOB │
  │ • TrueHD & E-AC3 audio  │                     │ • ISO & DVD stream dec. │
  │ • Ultra-low battery use │                     │ • RTSP / RTMP / MMS     │
  │ • HDR10+ / Dolby Vision │                     │ • UDP Multicast streams │
  └─────────────────────────┘                     └─────────────────────────┘
```

### 1. ExoPlayer Core (Google Media3)
- **Ultra-Efficient Pipeline**: Hardware-accelerated decoding with direct surface rendering.
- **Dolby Audio Decoders**: Integrated Jellyfin FFmpeg extension for native Dolby Digital (AC3), Dolby Digital Plus (E-AC3), and DTS playback.
- **HDR & Color Primaries**: Automatic tone-mapping and HDR10/Dolby Vision passthrough.

### 2. VLC Core (LibVLC 3.6 ARM64 Native)
- **Universal Software Decoders**: Play any file format, container, or exotic codec (FLV, WMV, VOB, TS, RMVB, OGV, 3GP) without re-encoding.
- **Network Streaming Protocols**: Full support for real-time live streams: RTSP, RTMP, HTTP Live Streaming (HLS), DASH, MMS, and UDP multicast.
- **Embedded Audio & Subtitle Demuxing**: High-precision demuxing of complex multi-track Matroska (.mkv) files.

---

## 🌟 Key Features

### 🎛️ Dynamic Floating Pill Controls
- **Spring-Physics Motion**: Left and right floating pill containers powered by Compose Spring physics (`Spring.DampingRatioLowBouncy`).
- **Smooth Collapsible Animations**: Fluid horizontal expand/shrink in landscape, vertical expand/shrink in portrait.
- **Synchronized 180° Chevron Rotation**: Smooth vector rotation when expanding or collapsing secondary toolbars.
- **Left Control Pill**: Playback speed (0.25x – 4.0x with pitch-preservation), A-B loop repetition, aspect ratio scaling (Fit, Crop, 16:9, 4:3, Fill), and Equalizer shortcut.
- **Right Track Pill**: Audio track selector, Subtitle track selector, Screen orientation lock, and Picture-in-Picture trigger.

### 💬 Unified Subtitle Engine
- **Universal Format Support**: SubRip (`.srt`), SubStation Alpha (`.ssa` / `.ass`), and WebVTT (`.vtt`).
- **Live Typography Customization**: Select fonts (Inter, Roboto, Monospace, Sans-Serif), customize text size (12sp – 48sp), adjust primary and outline colors, and tweak background box opacity.
- **Millisecond Precision Sync**: Calibration slider from `-5000 ms` to `+5000 ms` for audio/subtitle sync offsets.
- **Encoding Detection**: Automatic charset negotiation with manual override (UTF-8, UTF-16, ISO-8859-1, Windows-1252, GBK, Big5).

### 🎚️ Parametric Audio Suite
- **5-Band Equalizer**: 60Hz, 230Hz, 910Hz, 3.6kHz, and 14kHz adjustable bands.
- **Bass Boost & Virtualizer**: Punchy low-end enhancement up to +15dB and 3D audio space virtualization.
- **Audio Routing**: Multi-channel speaker mapping, headphone safety normalization, and seamless Bluetooth/wired headset transitions (`AUDIO_BECOMING_NOISY` handling).

### 👆 Intuitive Gesture HUD
- **Left Edge Swipe**: Screen brightness adjustments (0% – 100%) with haptic feedback.
- **Right Edge Swipe**: Media volume control (0% – 100%) independent of ringer volume.
- **Horizontal Scrubbing**: Rapid seek with thumbnail preview and elapsed/remaining timestamps.
- **Double-Tap Seeking**: Fast forward (+10s) and rewind (-10s).
- **Pinch-to-Zoom**: Smooth zoom-in up to 300% on any video frame.

### 📂 Smart Media Library & Folder Organization
- **Smart Folder Mode**: Group and view local videos organized by filesystem directories with badges and total durations.
- **Instant Search & Multi-Criteria Sort**: Filter videos by name, date added, file size, or resolution.
- **Comprehensive History & Bookmarks**: Resume video playback from the exact millisecond where you left off.
- **Home Screen App Widgets**: 4x1 and 2x2 Android home screen interactive widgets.

### 🤖 CLI Automation Broadcast Receiver
Automate testing, benchmarking, or headless playback control via ADB:
```bash
# Play / Pause toggle
adb shell am broadcast -a com.aerotech.aeroplayer.ACTION_PLAY_PAUSE

# Seek playback by offset
adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "seek_by" --el offset_ms 10000

# Switch playback engine dynamically
adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "engine" --es engine "VLC"
```

---

## 🏷️ Package Migration to `com.aerotech.aeroplayer`

The codebase is structured under the official package namespace:
```
com.aerotech.aeroplayer
 ├── cast               # Google Cast & Remote Media Routing
 ├── data               # Room Database, DAOs, Entities & Media Repositories
 ├── domain             # Clean Architecture Use Cases & Domain Models
 ├── player             # Dual Engine (ExoPlayer + VLC) & Unified Subtitles
 ├── receiver           # Lockscreen & ADB Broadcast Receivers
 ├── service            # Foreground Media Playback Service & PiP
 ├── ui                 # Jetpack Compose Screens, M3 Theme & Components
 └── util               # Content Resolvers, Format Detectors & Helpers
```

### 🛠️ Single-Run Package Renaming Script
To automate package and folder refactoring in a single command, run `./rename_package.sh`:
```bash
chmod +x ./rename_package.sh
./rename_package.sh com.example com.aerotech.aeroplayer
```
The script updates all source files, manifests, Gradle configurations, and directory trees idempotently.

---

## 🚀 CI/CD Release Workflow

Aero Player includes an enterprise-grade GitHub Actions workflow (`.github/workflows/release.yml`) for automated signed APK generation and release publishing.

### 🔑 Configuring GitHub Secrets
Add the following secrets to your GitHub repository (`Settings -> Secrets and variables -> Actions`):

| Secret Name | Description | Example / Format |
|---|---|---|
| `KEYSTORE_BASE64` | Base64-encoded release `.keystore` or `.jks` file | `cat release.keystore \| base64 -w 0` |
| `KEYSTORE_PASSWORD` | Password for the release keystore | `your_keystore_password` |
| `KEY_ALIAS` | Key alias name inside the keystore | `aerokey` or `release_alias` |
| `KEY_PASSWORD` | Password for the key alias | `your_key_password` |

*(Note: If no secret is configured, the workflow uses the repository's fallback keystore for zero-setup builds).*

### 🏷️ Triggering a Release
Push a git version tag:
```bash
git tag -a v1.4.6 -m "Release v1.4.6: Standalone Dual-Engine Player"
git push origin v1.4.6
```
The GitHub Action will:
1. Compile the app with JDK 17 and Gradle.
2. Sign and align `AeroPlayer-v1.4.6.apk`.
3. Compute SHA256 checksums.
4. Publish a GitHub Release with full release notes and downloadable APK artifacts.

---

## 🛠️ Building & Development

### Prerequisites
- Android Studio Ladybug / Meerkat or Android SDK 36
- Java Development Kit (JDK) 17+
- Gradle 8.10+ / 9.x

### Build Commands
```bash
# Compile debug build
gradle assembleDebug

# Compile release build
gradle assembleRelease

# Run unit tests
gradle :app:testDebugUnitTest
```

---

<div align="center">
  <sub>Made with 🍁 By <b>Shubh jain</b> · Aero Player Engine v1.4.6</sub>
</div>

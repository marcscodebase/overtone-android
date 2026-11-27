# Overtone (Android)

**Overtone** (`io.saier.overtone`) is a native, audiophile Android music client for **Jellyfin**, built with Kotlin, Jetpack Compose, Pure Material You, and AndroidX Media3.

Overtone is designed as a platform-native client family across **Android**, **macOS**, and **GNU/Linux (GNOME)** — embracing each platform's native look and ergonomics while sharing full feature parity.

## Highlights

- **Audiophile Lossless Playback:** Gapless Direct Play (FLAC, ALAC, WAV, AIFF, Opus, AAC, MP3) with 24-bit float PCM output, native **Android 14+ Bit-Perfect USB DAC** support, live seamless quality switching, and custom ReplayGain loudness normalization.
- **3-Zone Home & Discovery:** Compact 2×3 *Jump Back In* speed-dial, *Made For You* smart mixes (*Daylist*, *On Repeat*, live *Jellyfin Instant Mix* with offline fallback, *Time Capsule*, *Deep Cuts*), *Album Spotlight*, Genre & Era mixes, and curated rediscovery shelves.
- **Personal Library & Full Catalog:** Curated Spotify-style personal `Library` with pinning and playlist folders alongside a dedicated `Catalog` tab for browsing huge Jellyfin servers cleanly.
- **Built-In App-Only WireGuard Tunnel:** Embedded `wireguard-go` + `gVisor netstack` userspace tunnel with automatic Home LAN detection and zero Android system VPN slot usage.
- **Offline Downloads & Hybrid MP3 Compression:** Save untouched lossless files or compressed MP3s (server-side transcoding with on-device LAME fallback) to `Music/Overtone/` with embedded artwork, ReplayGain, and synced lyrics.
- **Pure Material You & One-Handed Ergonomics:** Dynamic wallpaper theming with zero hardcoded colors, vertical alphabetical fast-scroller with opposite-side index rail, and optional **Left-Handed Mode**.

See [doc/features.md](doc/features.md) for the complete feature reference.

## Installation

### Obtainium (GitHub Releases)
1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add this repository URL (`https://github.com/marcscodebase/android-music-player`).
3. Obtainium automatically tracks new Semantic Versioning releases (`vX.Y.Z`) and APK assets.

### F-Droid
Overtone is 100 % free and open-source software (no Google Play Services, no proprietary SDKs, reproducible native builds, Fastlane store metadata included) and prepared for F-Droid distribution.

## Building from Source

### Prerequisites
- JDK 17+ (e.g. Android Studio JBR or Temurin 17/21)
- Android SDK (API 35) & Android NDK `27.2.12479018`
- Go `1.24+` (for building the embedded userspace WireGuard JNI library)

### Build Steps
```bash
# 1. Build reproducible userspace WireGuard native libraries (arm64-v8a & x86_64)
./wireguard-core/build-android.sh

# 2. Run unit tests
./gradlew testDebugUnitTest

# 3. Build APK
./gradlew assembleRelease
```

To sign release builds locally, create a gitignored `keystore.properties` (or set `OVERTONE_KEYSTORE_PATH`, `OVERTONE_KEYSTORE_PASSWORD`, `OVERTONE_KEY_ALIAS`, `OVERTONE_KEY_PASSWORD` environment variables).

## License

Overtone is free software licensed under the **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)**. See [LICENSE](LICENSE) for the full text.

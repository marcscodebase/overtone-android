# `android-music-player` (`music-player`) — Features

A complete overview of all features currently implemented in **android-music-player** (Android app display name: **`music-player`**), a native Android lossless Jellyfin music player built with Kotlin, Jetpack Compose, Pure Material You, and AndroidX Media3.

---

## 1. Jellyfin Connection, Auth & Two-Way Sync
- **Flexible Server Support:** Connects to personal Jellyfin servers over **HTTP and HTTPS**, including local LAN IPs, custom ports, Tailscale hostnames, reverse proxies, and user-installed CA certificates for private home-lab PKIs.
- **Two Sign-In Methods:** Supports both **Username / Password** authentication and **Jellyfin Quick Connect** (with live polling and code regeneration).
- **Music Library Selection:** Automatically discovers Jellyfin Music libraries and lets you choose which libraries to include.
- **Encrypted Credential Storage:** Stores server sessions and access tokens encrypted at rest using **Android Keystore (AES-256-GCM)**; tokens and passwords are never written to logs.
- **Two-Way Jellyfin Sync:**
  - **Playback Reporting:** Reports playback start, periodic progress, and stop events so Jellyfin play counts, *Last Played*, and *Recently Played* stay up to date.
  - **Favorites / Likes:** Optimistic local updates for tracks, albums, and artists synced back to Jellyfin.
  - **Playlists:** Full CRUD (create, rename, delete, add/remove tracks, reorder tracks) synced directly to Jellyfin.
  - **Offline Sync Outbox:** Queues favorites, playback stops, and playlist edits made while offline in Room and flushes them automatically via WorkManager once reconnected (plus manual *Sync now* in Settings).

---

## 2. Built-In App-Only Userspace WireGuard Tunnel
- **Zero System VPN Required:** Embeds official **`wireguard-go` + `gVisor netstack`** directly inside the app process (`libwguserspace.so`, 16 KB page-aligned for Android 15+) without using Android's `VpnService`.
- **100 % App-Isolated:** Only `music-player` connects to your home network; all other Android apps remain untouched, no Android VPN slot is occupied, no system VPN icon appears, and another system VPN can run in parallel.
- **Authenticated Loopback Bridge:** Routes all app networking — **Jellyfin API, ExoPlayer streams, Lyrics API, Coil cover art, and background downloads** — through a loopback-only (`127.0.0.1`) HTTP & HTTPS (`CONNECT`) proxy secured with a per-launch random 256-bit token and internal home-network DNS resolution (e.g. `.fritz.box`, `.home.arpa`).
- **Three Tunnel Modes:**
  - **Off:** Direct connections only.
  - **Auto (Smart Home Detection):** Connects directly on home Wi-Fi for full LAN speed and minimal battery use; automatically starts WireGuard on mobile data or external Wi-Fi (including automatic re-evaluation on Wi-Fi-to-Wi-Fi and Wi-Fi-to-cellular handoffs).
  - **Always:** Always routes `music-player` traffic through the WireGuard tunnel.
- **Easy Setup & Diagnostics:**
  - Import standard `wg-quick` `.conf` files (FritzBox, wg-easy, PiVPN, OPNsense) via file picker or paste dialog, supporting IPv4/IPv6 endpoints and optional `PresharedKey`.
  - WireGuard private keys are stored **AES-256-GCM encrypted in the Android Keystore**.
  - Accessible both from **Settings → Home Network (WireGuard)** and directly from the **Login screen** for first-time setup away from home.
  - Live status card showing tunnel state, handshake age, RX/TX byte counters, and an end-to-end **Test connection** button with latency readout.

---

## 3. Pure Material You UI & Gesture Navigation
- **Strict Pure Material You Theming:** Uses `dynamicDarkColorScheme` / `dynamicLightColorScheme` derived strictly from the system wallpaper and Light/Dark mode with zero hardcoded UI hex colors and full edge-to-edge rendering.
- **Swipeable 2-Tab Navigation:** Single-row top bar (`Home` | `Library`) backed by a smooth `HorizontalPager` with no bottom navigation bar.
- **Contextual Floating Action Buttons:**
  - **Home:** Floating **Search** FAB.
  - **Library:** Stacked **Search** FAB, matching **Shuffle Library** FAB above it, and a smaller **Create Playlist (`+`)** FAB on top.
- **Persistent Floating Mini-Player:** Floats above the system navigation bar on browse and detail screens with album art, title/artist, quick `+`/`✓` save button, play/pause, thin progress bar, horizontal swipe to skip tracks, and tap to expand.
- **Natural Touch Gestures:**
  - **Pull down to dismiss** detail screens (*Album*, *Artist*, *Artist Collections*, *Playlist*, *Daylist*) smoothly from the top of the list back to the previous view.
  - **Swipe right on any song row** for instant Spotify-style *Add to Queue* with haptic feedback.
  - **Swipe left / right in Now Playing** to skip to the next or previous track with animated cover-art tracking.

---

## 4. Home, Daylist & Unified Search
- **Daylist (50-Track Auto-Refreshing Mix):**
  - Prominent Home card with a 2×2 cover mosaic, **Morning Mix** (`00:00–12:00`) / **Evening Mix** (`12:00–00:00`) indicator, next refresh time, and one-tap **Play** & **Shuffle** buttons.
  - Deterministically generates **50 shuffled songs** from your library twice daily (`00:00` and `12:00`) and opens a full dedicated 50-song detail screen.
- **Home Feed:** Daylist, Recently Added Albums, Your Playlists, Favorites, and Recently Played, with pull-to-refresh and top-bar Refresh.
- **Unified Online & Offline Search:** Live Jellyfin search across songs, albums, artists, and playlists, automatically falling back to cached local Room search when offline (prioritizing downloaded tracks).

---

## 5. Library, Playlists, Folders & Artist Discography
- **Library Filters & Quick Controls:** Filter chips for `Downloaded` (placed first for quick offline access), `Playlists`, `Albums`, `Artists`, and `Songs`, paired with Alphabetical / Recently Added sorting and a quick Offline Mode toggle in the top bar.
- **Playlist Folders:** Group playlists into collapsible folders via `<Folder> / <Playlist>` naming and Jellyfin `folder:<Folder>` tags, with actions to create in a folder, move between folders, rename folders, and ungroup.
- **Quick Save & Playlist Picker (`+` / `✓`):** Available on the Mini-Player, Full-Screen Player, and every track row to toggle *Liked Songs* and add/remove a track across multiple playlists in one sheet.
- **Enhanced Artist View:**
  - **Liked Songs Row:** Compact Spotify-style shortcut right under Play/Shuffle (shown when you have ≥ 1 favorited song by the artist) opening all favorited songs by that artist.
  - **Personal Most Played (Top 5 → Top 10):** Ranked list of your most-played tracks by the artist (displaying rank and play count, filling remaining slots with additional artist tracks if fewer have been played) with an inline **Show top 10 / Show less** toggle.
  - **Chronological All Songs & All Albums:** Dedicated **View all songs** (grouped by release year) and **View all** albums views sorted by Jellyfin `PremiereDate` & `ProductionYear`, defaulting to *Newest first* with a quick toggle to *Oldest first*.
  - **Multi-Artist Navigation:** Stores all track artists; clickable artist headers on Album screens and *Go to album* / *Go to artist* pickers across menus and Now Playing.

---

## 6. Audiophile Lossless Playback & Quality Controls
- **Lossless Direct Play (Media3 / ExoPlayer):** Gapless native playback of lossless formats (FLAC, ALAC, WAV, AIFF) plus Opus, AAC, and MP3 directly from Jellyfin on Wi-Fi without transcoding.
- **Mobile Data Bitrate Cap:** Defaults to *Original (Lossless)* on cellular, with an optional Settings cap (`320`, `256`, `192`, `128 kbps` MP3).
- **Live Seamless Quality Switcher:** Tap the audio format/source badge in Now Playing (e.g. `FLAC • 24-bit/96 kHz` or `MP3 • 320 kbps • Local`) to switch seamlessly at the exact current timestamp across **Auto**, **Downloaded (Local)** (when available), **Original (Lossless)**, and **320 / 256 / 192 / 128 kbps MP3**. Stays automatically synced whenever quality settings, network type, or audio output devices change.
- **Audio Output Detection & Smart Matching:**
  - Bottom-left output indicator in Now Playing showing active device name, codec, and quality capability (Phone Speaker, Wired, USB DAC, Bluetooth A2DP, Bluetooth LE Audio).
  - Dedicated **Audio Output** sheet with Android system output switcher shortcut and per-Bluetooth-device **Hi-Res / LDAC** override toggle.
  - Optional Settings toggle **Match quality to output device** (streams lossless on wired/USB/speaker/Hi-Res BT; caps standard Bluetooth to `320 kbps MP3`).
- **ReplayGain Loudness Normalization:** Custom Media3 `AudioProcessor` supporting **Off (Bit-exact)**, **Track gain**, and **Album gain**, plus configurable preamp (`-6.0 dB` to `+6.0 dB`) with soft peak limiting.
- **Background & Hardware Controls:** Media3 `MediaLibraryService` + `MediaSession` providing foreground media notifications, lock-screen controls, Bluetooth transport buttons, audio focus handling, and automatic pause when headphones disconnect.

---

## 7. Queue Management, True Shuffle & Synced Lyrics
- **True Physical Shuffle & Instant Re-Shuffle:**
  - Shuffling any album, playlist, artist, daylist, or library genuinely randomizes the queue order so playback never starts stuck on track #1 and the Queue view always shows the real upcoming tracks.
  - Tapping Shuffle again immediately starts a different random track with a freshly shuffled queue; turning Shuffle off in the player restores the original context order without interrupting the playing song.
- **Spotify-Style Queue & Last Played Sheet:**
  - Swipeable 2-tab sheet (**Queue** | **Last Played**) with automatic scroll-to-top reset when switching tabs.
  - Priority **Next in Queue** section for manually queued tracks above **Next from \<context\>**, drag-handle reordering, one-tap removal, Clear Manual Queue / Clear All, and **Reshuffle Queue** button (when Shuffle is active, reshuffles upcoming tracks only without interrupting Now Playing).
  - Full playback queue and position persisted in Room across app and service restarts.
- **Synced & Plain Lyrics:**
  - Loads synced and plain lyrics from Jellyfin's Lyrics API with automatic offline fallback to embedded ID3v2 (`USLT`/`SYLT`) and FLAC Vorbis tags.
  - **Live Player Preview:** Compact synced lyric banner above the quality chip in Now Playing using frame-accurate lead-time timing so the slide/fade transition finishes precisely on each LRC timestamp.
  - **Full-Screen Lyrics Viewer:** Auto-scrolling highlighted lines with tap-to-seek to any timestamp.

---

## 8. Offline Downloads, Hybrid MP3 Transcoding & File Verification
- **Flexible Download Scopes & Quality:** Download single tracks, full albums, or full playlists in **Original (Lossless)** or **MP3 (`320 / 256 / 192 / 128 kbps`)** with global default quality, Wi-Fi-only toggle, and optional *Ask quality every time* picker sheet.
- **Hybrid MP3 Compression Pipeline:**
  1. Requests server-side MP3 transcoding from Jellyfin first.
  2. Automatically falls back to downloading the original lossless file and transcoding **on-device** (`MediaCodec` PCM decode + `jump3r` LAME MP3 encoder) if server transcoding fails or is unavailable.
- **Public `Music/` Storage (`MediaStore`) & Tag Preservation:**
  - Writes files atomically (`IS_PENDING`) to `Music/music-player/<Artist>/<Album>/<TrackNo> - <Title>.<ext>`.
  - Embeds complete ID3v2.4 (MP3) / Vorbis Comment (FLAC) metadata, album artwork, ReplayGain, lyrics, and verification tags (`MUSICPLAYER_TRACK_ID`, `MUSICPLAYER_QUALITY`, `MUSICPLAYER_AUDIO_SHA256`).
- **Automatic Offline Folder Recognition & Deduplication:**
  - Uses `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (Android 12) to discover and adopt existing verified files in `Music/music-player/` across reinstalls or debug-to-release upgrades.
  - Verifies identity via embedded Track ID / audio-payload SHA-256 (plus strict path, duration, and tag verification for legacy files) and prevents/cleans up ` (1)` duplicate files.
- **Reference-Counted Deletion & Storage Manager:** Tracks ownership across tracks, albums, and playlists so removing one playlist never deletes songs still downloaded via another album or playlist; *Settings → Downloads & Storage* shows total space used, lossless vs. MP3 breakdown, **Verify files**, and **Clear all**.
- **Downloaded Playlist Auto-Sync:** Per-playlist **Auto-Sync Downloads** toggle with periodic and manual WorkManager sync that downloads newly added tracks and garbage-collects removed tracks automatically.
- **Local-First Playback & Offline Mode:** Automatically plays verified local files whenever downloaded (even when online); manual **Offline Mode** toggle in Settings and Library top bar with dimmed unavailable tracks and offline banners.

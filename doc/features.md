# `android-music-player` (`music-player`) — Features

A complete, code-verified overview of all features currently implemented in **android-music-player** (Android app display name: **`music-player`**), a native Android 12–15 (`minSdk 31`, `targetSdk 35`) lossless Jellyfin music player built with Kotlin, Jetpack Compose, Pure Material You, and AndroidX Media3.

---

## 1. Jellyfin Connection, Auth & Two-Way Sync
- **Flexible Server Support:** Connects to personal Jellyfin servers over **HTTP and HTTPS**, including local LAN IPs, custom ports, Tailscale hostnames, reverse proxies, and user-installed CA certificates for private home-lab PKIs, with automatic `http://` vs. `https://` URL normalization for local/private/Tailscale hosts.
- **3-Step Sign-In & Library Picker:** Supports **Username / Password** and server-enabled **Jellyfin Quick Connect** (live 3-second polling and code regeneration), followed by automatic Music library discovery and selection (auto-skipped when only one music library exists).
- **Encrypted Credential Storage & Header Auth:** Stores server sessions and access tokens encrypted at rest using **Android Keystore (AES-256-GCM)**. Injects `Authorization` and `X-Emby-Token` headers dynamically per request so tokens never appear in stream/download URLs or logs.
- **Two-Way Jellyfin Sync:**
  - **Playback Reporting:** Reports start, 15-second periodic progress, pause/resume, and stop events (requiring ≥ 5 s playback to increment local play counts) so Jellyfin play counts, *Last Played*, and *Recently Played* stay up to date.
  - **Favorites / Likes:** Optimistic local updates for tracks, albums, and artists synced back to Jellyfin.
  - **Playlists:** Full CRUD (create, rename, delete, add/remove tracks, reorder tracks) synced directly to Jellyfin.
  - **Offline Sync Outbox:** Queues favorites, playback stops, and playlist edits (rename, folder move, add/remove/reorder tracks) made while offline in Room, flushing eagerly on reconnect and via WorkManager retries (plus manual *Sync now* in Settings). Creating and deleting playlists requires an online connection.

---

## 2. Built-In App-Only Userspace WireGuard Tunnel
- **Zero System VPN Required:** Embeds official **`wireguard-go` + `gVisor netstack`** directly inside the app process (`libwguserspace.so`, 16 KB page-aligned for Android 15+, bundled for `arm64-v8a` and `x86_64` with graceful fallback on 32-bit devices) without using Android's `VpnService`.
- **100 % App-Isolated:** Only `music-player` connects to your home network; all other Android apps remain untouched, no Android VPN slot is occupied, no system VPN icon appears, and another system VPN can run in parallel.
- **Authenticated Loopback Bridge:** Routes all app networking — **Jellyfin API, ExoPlayer streams, Lyrics API, Coil cover art, and background downloads** — through a loopback-only (`127.0.0.1`) HTTP & HTTPS (`CONNECT`) proxy secured with a fresh random 256-bit token on every tunnel start/restart, internal home-network DNS resolution (e.g. `.fritz.box`, `.home.arpa`), and automatic OkHttp connection-pool eviction on tunnel transitions.
- **Three Tunnel Modes:**
  - **Off:** Direct connections only.
  - **Auto (Smart Home Detection):** Connects directly on home Wi-Fi (or whenever the Jellyfin server is publicly reachable); automatically starts WireGuard for private/local home hosts or when direct probing fails across Wi-Fi and cellular handoffs.
  - **Always:** Always routes `music-player` traffic through the WireGuard tunnel.
- **Easy Setup & Diagnostics:**
  - Import standard `wg-quick` `.conf` files (FritzBox, wg-easy, PiVPN, OPNsense) via file picker or paste dialog, supporting IPv4/IPv6 endpoints, optional `PresharedKey`, and sensible defaults (`MTU 1280`, `25 s` keepalive, `1.1.1.1` fallback DNS).
  - The entire WireGuard configuration (private key, preshared key, peer key, endpoint, tunnel IPs, DNS) is stored **AES-256-GCM encrypted in the Android Keystore**.
  - Accessible both from **Settings → Home Network (WireGuard)** and directly from the **Login screen** for first-time setup away from home.
  - Live status card (5 states, 3-second polling) showing handshake age and RX/TX byte counters, profile summary with replace/remove controls, and an end-to-end **Test connection** button with latency readout.

---

## 3. Pure Material You UI & Gesture Navigation
- **Strict Pure Material You Theming:** Uses `dynamicDarkColorScheme` / `dynamicLightColorScheme` derived strictly from the system wallpaper and Light/Dark mode with zero hardcoded UI hex colors, edge-to-edge rendering, animated shimmer loading placeholders, retry/empty states, and Coil 3 artwork caching (20 % RAM + 150 MB disk cache).
- **Swipeable 2-Tab Navigation:** Single-row top bar (`Home` | `Library`) backed by a smooth `HorizontalPager` with no bottom navigation bar.
- **Contextual Floating Action Buttons:**
  - **Home:** Floating **Search** FAB.
  - **Library:** Stacked **Search** FAB, context-aware **Shuffle Library** FAB above it (shuffles *Downloaded* when on the `Downloaded` filter or offline, and all tracks otherwise), and a smaller **Create Playlist (`+`)** FAB on top.
- **Persistent Floating Mini-Player:** Floats above the system navigation bar on browse and detail screens (hidden on Settings, Storage, and WireGuard screens) with album art, title/artist, quick `+`/`✓` save button, play/pause with buffering indicator, thin progress bar, horizontal swipe to skip tracks, and tap to expand.
- **Natural Touch Gestures:**
  - **Pull down to dismiss** detail screens (*Album*, *Artist*, *Artist Collections*, *Playlist*, *Daylist*) smoothly from the top of the list or top bar (automatically disabled during Playlist Reorder Mode).
  - **Swipe right on playable song rows** for instant Spotify-style *Add to Queue* with haptic feedback (disabled on unavailable offline rows, Last Played, and Playlist Reorder Mode).
  - **Swipe left / right in Now Playing** to skip to the next or previous track with animated cover-art tracking.

---

## 4. Home, Daylist & Unified Search
- **Daylist (50-Track Auto-Refreshing Mix):**
  - Prominent Home card with a 2×2 cover mosaic, **Morning Mix** (`00:00–12:00`) / **Evening Mix** (`12:00–00:00`) indicator, next refresh time, and one-tap **Play** & **Shuffle** buttons.
  - Deterministically generates **50 shuffled songs** from your library twice daily (`00:00` and `12:00`), auto-checks for rollover on app resume, opens a full 50-song detail screen with total duration, and degrades gracefully offline by playing/shuffling downloaded Daylist tracks.
- **Home Feed:** Daylist, Recently Added (up to 16 albums), Your Playlists, Favorites (up to 10), and Recently Played (up to 10), with pull-to-refresh, top-bar Refresh (which also reconciles offline downloads), and an Offline status banner with a one-tap **Go online** action when manual Offline Mode is active.
- **Unified Online & Offline Search:** Auto-focused, 300 ms debounced live Jellyfin search across songs, albums, artists, and playlists, automatically falling back to cached local Room search when offline with a visible local-results indicator banner.

---

## 5. Library, Playlists, Folders & Artist Discography
- **Library Filters & Quick Controls:** Filter chips for `Downloaded` (placed first for quick offline access), `Playlists`, `Albums`, `Artists`, and `Songs`, paired with a quick Offline Mode toggle (toggling manual Offline Mode on automatically switches to `Downloaded`) and a Sort toggle (Alphabetical vs. Recently Added for Albums / Recently Played for Downloaded & Songs).
- **Playlist Folders:** Organize playlists into drill-down folders via `<Folder> / <Playlist>` naming and best-effort Jellyfin `folder:<Folder>` tags, with quick-pick existing-folder chips, pre-filled folder when creating from inside a folder, move between folders, rename folders, and ungroup.
- **Playlist Management & Reorder Mode:** Top-bar Download/Remove, toggleable **Reorder Mode** (move up/down arrows and inline delete), overflow actions (*Move to folder*, *Rename playlist*, *Delete playlist*), *Remove from playlist* track action, and an inline **Sync downloads** button inside the Auto-Sync card.
- **Quick Save & Playlist Picker (`+` / `✓`):** Available on the Mini-Player, Full-Screen Player, and every track row (`✓` reflects favorited or in any playlist). Tapping `+` on an unfavorited track immediately saves it to **Favorites** and opens the multi-playlist sheet, which also includes an inline **New Playlist** creator.
- **Enhanced Artist & Album Views:**
  - **Album Detail:** Top-bar Download/Remove and Favorite heart, track numbers, total duration, and clickable artist headers.
  - **Artist Liked Songs Row:** Compact Spotify-style shortcut right under Play/Shuffle (shown when you have ≥ 1 favorited song by the artist) opening all favorited songs by that artist with Play/Shuffle controls.
  - **Personal Most Played (Top 5 → Top 10):** Ranked list of your most-played tracks by the artist (displaying rank and play count, filling remaining slots with additional artist tracks if fewer have been played) with an inline **Show top 10 / Show less** toggle.
  - **Chronological All Songs & All Albums:** Dedicated **View all songs** (grouped by release year, with Play/Shuffle) and **View all** albums views sorted by `ProductionYear` with `PremiereDate` tie-breaking, defaulting to *Newest first* with a quick toggle to *Oldest first*.
  - **Multi-Artist Navigation:** Stores all track artists with *Go to album* / *Go to artist* pickers across track menus and Now Playing.

---

## 6. Audiophile Lossless Playback & Quality Controls
- **Lossless Direct Play (Media3 / ExoPlayer):** Gapless native Direct Play (`/Audio/{id}/stream?static=true`) of lossless formats (FLAC, ALAC, WAV, AIFF) plus Opus, AAC, and MP3 directly from Jellyfin on Wi-Fi without transcoding, with float audio output, CBR seeking, and a 3-second previous-track restart rule.
- **Mobile Data Bitrate Cap & Smart Metered Detection:** Defaults to *Original (Lossless)* on cellular, with an optional Settings cap (`320`, `256`, `192`, `128 kbps` MP3). Detects both metered networks and 5G cellular transports; when both cellular and output-device caps apply, the lower bitrate wins.
- **Live Seamless Quality Switcher & HLS Transcoding:** Tap the audio format/source badge in Now Playing (e.g. `FLAC • 24-bit/96 kHz` or `MP3 • 320 kbps • Local`) to switch seamlessly at the exact millisecond across **Auto**, **Downloaded** (when available), **Original (Lossless)**, and **320 / 256 / 192 / 128 kbps MP3** (powered by Jellyfin HLS VOD transcoding for instant seeking). Stays live-synced whenever quality settings, network type, or audio output devices change without unnecessary reloads.
- **Audio Output Detection & Smart Matching:**
  - Detects **Phone Speaker, Wired, USB DAC, Bluetooth A2DP, Bluetooth LE Audio, and HDMI / Line-Out** (using Android 13+ `getAudioDevicesForAttributes` with Android 12 `MediaRouter2` fallback).
  - Bottom-left output indicator in Now Playing and a dedicated **Audio Output** sheet listing all detected devices with PCM bit-depth/sample-rate capabilities, an Android system output switcher shortcut, and a per-Bluetooth-device **Hi-Res / LDAC** override toggle.
  - Optional Settings toggle **Match quality to output device** (streams lossless on wired/USB/speaker/HDMI/Hi-Res BT; caps standard Bluetooth to `320 kbps MP3`).
- **ReplayGain Loudness Normalization:** Custom Media3 `AudioProcessor` supporting **Off (Bit-exact)**, **Track gain**, and **Album gain** (falls back to track gain when album gain metadata is absent), configurable preamp (`-6.0 dB` to `+6.0 dB` in `0.5 dB` steps), smooth gain ramping, `tanh` soft-knee peak limiting, and protection so untagged tracks are never boosted above `0 dB`.
- **Background & Hardware Controls:** Media3 `MediaLibraryService` + `MediaSession` providing foreground media notifications, lock-screen controls, Bluetooth transport buttons, automatic audio focus/ducking, and auto-pause when headphones disconnect.

---

## 7. Queue Management, True Shuffle & Synced Lyrics
- **True Physical Shuffle & Instant Re-Shuffle:**
  - Shuffling any album, playlist, artist, daylist, or library genuinely randomizes the queue order so playback never starts stuck on track #1 and the Queue view always shows the real upcoming tracks.
  - Tapping Shuffle again immediately starts a different random track with a freshly shuffled queue; turning Shuffle off in the player restores the original context order without interrupting the playing song.
- **Spotify-Style Queue & Last Played Sheet:**
  - Swipeable 2-tab sheet (**Queue** | **Last Played** — up to 50 recent tracks with tap-to-play, Save `+`/`✓`, and overflow menu) with automatic scroll-to-top reset when switching tabs.
  - Pinned Now Playing row, priority **Next in Queue** section for manually queued tracks above **Next from \<context\>**, tap-to-jump, drag-handle reordering with haptic/elevation feedback, swipe/tap removal, `Clear` / `Clear all`, and **Reshuffle Queue** (when Shuffle is active with ≥ 2 upcoming tracks).
  - Playback queue persisted in Room and current index/position persisted in DataStore across app and service restarts.
- **Now Playing Quick Actions:** Tap `"Playing from <Album>"` in the top bar to jump straight to the album, tap the track title/artist for quick album/artist navigation, and manage per-track downloads directly via the 4-state download button (Download / Cancel / Remove / Retry) beside `+`/`✓`.
- **Synced & Plain Lyrics:**
  - Loads synced and plain lyrics from Jellyfin's Lyrics API (with a 32-entry in-memory LRU cache and multi-timestamp LRC support) with automatic offline fallback to embedded ID3v2 (`USLT`) and FLAC Vorbis (`LYRICS`) tags.
  - **Live Player Preview:** Compact tappable synced lyric banner above the quality chip in Now Playing using frame-accurate lead-time timing so the slide/fade transition finishes precisely on each LRC timestamp.
  - **Full-Screen Lyrics Viewer:** Source/sync status chip (`Synced • Jellyfin`, `Synced • Local`, `Plain`), auto-scrolling highlighted lines, tap-to-seek with haptic feedback, and an embedded progress bar + Play/Pause button.

---

## 8. Offline Downloads, Hybrid MP3 Transcoding & File Verification
- **Flexible Download Scopes & Quality:** Download single tracks, full albums, or full playlists in **Original (Lossless)** or **MP3 (`320 / 256 / 192 / 128 kbps`)** with global default quality, Wi-Fi-only toggle, optional *Ask quality every time* picker sheet, foreground download/transcoding notifications, and up to 3 automatic retries with exponential backoff.
- **Hybrid MP3 Compression Pipeline:**
  1. Requests server-side MP3 transcoding from Jellyfin first and sniffs the audio header to verify real MP3 frames (rejecting error pages or non-MP3 payloads).
  2. Automatically falls back to downloading the original lossless file and transcoding **on-device** (`MediaCodec` PCM decode + `jump3r` CBR LAME MP3 encoder) if server transcoding fails or is unavailable.
- **Public `Music/` Storage (`MediaStore`) & Tagging:**
  - Writes files atomically (`IS_PENDING`) to `Music/music-player/<Artist>/<Album>/<TrackNo> - <Title>.<ext>` (using `<DiscNo>-<TrackNo>` for multi-disc albums, with sanitized filenames).
  - Embeds ID3v2.4 (MP3) / Vorbis Comment (FLAC) metadata, album artwork, Track ReplayGain, Jellyfin synced/plain lyrics (for full offline lyrics support), and verification tags (`MUSICPLAYER_TRACK_ID`, `MUSICPLAYER_QUALITY`, `MUSICPLAYER_AUDIO_SHA256`), while preserving existing FLAC Vorbis Comments (such as genre, album ReplayGain, precise release dates, and MusicBrainz IDs).
- **Automatic Offline Folder Recognition & Deduplication:**
  - Uses `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (Android 12) to auto-discover and adopt existing verified files in `Music/music-player/` on sign-in, Home refresh, or manual verification across reinstalls or debug-to-release upgrades.
  - Verifies identity via embedded Track ID (or strict path, `±3 s` duration, and tag checks for legacy files), reuses identical files via audio-payload SHA-256 on Scoped Storage conflicts, and cleans up ` (1)` duplicates via full-file SHA-256.
- **Reference-Counted Deletion & Storage Manager:** Tracks ownership across tracks, albums, and playlists so removing one playlist never deletes songs still downloaded via another album or playlist. **Settings → Downloads & Storage** displays total space used, lossless vs. MP3 breakdown, a full per-track download queue & file list (live status, errors, codec/bitrate, `Original` / `Server` / `On-device` badge, and per-track Cancel / Retry / Delete), **Verify files**, and **Clear all**.
- **Downloaded Playlist Auto-Sync:** Per-playlist **Auto-Sync Downloads** toggle backed by a 6-hour periodic (plus reconnect and manual) WorkManager sync that downloads newly added tracks and reference-count prunes removed tracks automatically.
- **Local-First Playback & Offline Mode:** Automatically plays verified local files whenever downloaded (even when online); manual **Offline Mode** toggle in Settings and Library top bar with offline banners and dimmed unavailable tracks.

# Midas goals and roadmap

Midas is a separate desktop + Android video player built from Stash's player, with FFmpeg built in.
It plays any codec and container, including the subtitle and audio tracks inside a file, and works as
the system's default player. Stash keeps its own built-in player, built from the same shared code.

Status: the standalone desktop app and an initial Android MVP are implemented; Android device
validation and Stash integration remain. See [README.md](README.md) for current features. Items marked
**verify** are assumptions to check before relying on them.

---

## 1. Why a separate app

| Today (inside Stash) | Standalone player |
|---|---|
| Only plays Hydrus files | Plays local files, folders, URLs, Hydrus, "Open with…" from anywhere |
| Windows decodes with Media Foundation: HEVC/AV1 need paid extensions | FFmpeg decodes everything, with hardware acceleration where available |
| Can't see tracks inside a file (e.g. the English track in the Fate episodes) | Lists every embedded audio and subtitle track |
| ASS styling stripped, signs moved to the top | Full ASS rendering via libass (phase 3) |
| No file associations, no PiP, no background audio | Registered as a video player on both platforms |

## 2. Identity

| | Decided | Notes |
|---|---|---|
| Name | **Midas** | Window title, launcher label, installer, config folder (`%APPDATA%\Midas`, `~/.config/midas`) |
| App id | **`app.auriel.midas`** | Android applicationId, macOS bundle id. **Can't change once published on Google Play.** |
| Version | 0.1.0 | Separate from Stash's version |
| Distribution | **Google Play + GitHub releases** | See "Distribution" below |
| Stash | **Keeps its built-in player** | Shared modules. Stash also gets an "Open in Midas" action |

## 3. Code layout

Keep one Gradle repo and split the player out of `:shared` into modules both apps use:

```
player-core/        KMP, no UI. Engine interface, track model, subtitle parsers
                    (moved from shared/.../subtitles), online subtitle sources, resume store.
player-ffmpeg/      Desktop JVM. FFmpeg engine (demux, decode, A/V sync, audio out).
player-android/     Android. Media3 engine + FFmpeg fallback, MediaSession service, PiP.
player-ui/          KMP Compose. The current VideoPlayer, controls, menus, subtitle finder,
                    now talking to the Engine interface instead of ComposeMediaPlayer.
midasApp-desktop/   Midas desktop entry point: file associations, single instance, window.
midasApp-android/   Midas Android entry point: intent filters, permissions, library screen.
shared/ (Stash)     Depends on player-ui + an engine. Its built-in player gets FFmpeg for free.
```

Code that moves as-is: `Subtitles.kt` (263 lines), `SubtitleSources.kt` (515), `SubtitleFinderDialog.kt`
(336), `PlayerIcons.kt`, the `Media.*.kt` helpers, and most of `VideoPlayer.kt` (742).
ComposeMediaPlayer is removed.

### Engine interface (player-core)

```kotlin
interface PlaybackEngine : AutoCloseable {
    val state: StateFlow<PlaybackState>        // idle / loading / ready / ended / error
    val clock: PlaybackClock                    // position in seconds; smooth, read every frame
    val duration: StateFlow<Double?>
    val tracks: StateFlow<MediaTracks>          // video, audio, subtitle, chapters
    fun open(source: MediaSource, startAt: Double = 0.0)
    fun play(); fun pause()
    fun seek(seconds: Double, exact: Boolean = false)   // keyframe seek while dragging, exact on release
    fun selectAudio(id: String?); fun selectSubtitle(id: String?)
    var volume: Float; var speed: Float; var audioDelay: Double
    fun frameStep(forward: Boolean)
    fun screenshot(): ImageBitmap?
}

sealed interface MediaSource {
    data class File(val path: String) : MediaSource
    data class ContentUri(val uri: String) : MediaSource      // Android SAF / MediaStore
    data class Http(val url: String, val headers: Map<String, String> = emptyMap()) : MediaSource
}
```

Embedded text subtitles come out of the engine as `SubtitleTrack`s, so the existing renderer,
delay and size controls work on them unchanged. With HTTP headers supported, Hydrus streams
no longer need the access key in the URL.

## 4. Playback engines

### Desktop: FFmpeg via JavaCPP

- Library: `org.bytedeco:ffmpeg` presets (prebuilt FFmpeg for Windows x64, macOS arm64/x64, Linux x64).
  Pin an exact version. Bundle only the current OS's natives in each installer.
- **Use the LGPL build.** Playback only needs decoders, and the LGPL build keeps the app's own
  licence free. **Verify** which decoders the LGPL build includes (expect all major video and
  audio codecs and ASS parsing).
- Pipeline, one thread each:
  1. **Demux:** `avformat_open_input` (also handles HTTP with range requests), packet queues per stream.
  2. **Video decode:** hardware first (`d3d11va` on Windows, `videotoolbox` on macOS,
     `vaapi` on Linux), copy frames back to memory, software fallback. `sws_scale` to BGRA.
  3. **Audio decode:** `swr_convert` to 48 kHz stereo float, out through `javax.sound` `SourceDataLine`.
  4. **Subtitle decode:** text tracks become `SubtitleCue`s. Bitmap tracks (PGS, VobSub)
     become image cues (phase 2).
- **Sync:** audio is the master clock. Video frames are shown at their PTS against
  `audio written − line latency`, dropping late frames.
- **Rendering:** upload each frame into a Skia `Image` drawn by a Compose `Canvas`.
  Target: 4K60 without dropped frames. Fallback if upload is too slow: a dedicated
  `SkiaLayer` / GPU texture path.
- **Risks:**
  - JavaSound latency varies by OS. Measure it and correct for it.
  - Frame upload at 4K60.
  - Getting seeking right (flush the queues, decode to the exact frame).

Alternatives considered:

| Option | For | Against |
|---|---|---|
| **FFmpeg (JavaCPP)** ✔ | Full control, one engine for all desktop OSes, Compose-native UI | We write sync/rendering ourselves |
| libmpv | Best playback quality, libass built in | Wants its own window or OpenGL context. Hard to put Compose controls on top. GPL by default. |
| VLCJ (libvlc) | Mature | Needs VLC installed or a large bundle. Same overlay problem. GPL/LGPL mix. |
| Keep ComposeMediaPlayer | No work | The codec and track gaps are why this rewrite exists |

### Android: Media3 (ExoPlayer) + FFmpeg

- **Main path: Media3 ExoPlayer.** Hardware MediaCodec decoding is fast and battery-friendly.
  It already reads MKV/MP4/WebM and exposes embedded text tracks.
- **FFmpeg:** Media3's FFmpeg decoder extension, built from source with the NDK. It adds audio
  codecs phones lack (e.g. some FLAC, DTS, TrueHD) and, in recent Media3 releases, experimental
  software video decoding for codecs the phone has no hardware for (**verify** version and codec list).
  Ship per-ABI splits (arm64-v8a, armeabi-v7a, x86_64).
- FFmpegKit is archived, so don't use it.
- ASS: evaluate a libass Media3 extension (e.g. the `libass-android` / `ass-media` projects) for
  phase 3. Until then, ASS goes through our own renderer as today.

## 5. Permissions and system integration

### Android

Manifest permissions:

| Permission | Why |
|---|---|
| `INTERNET` | Streams, subtitle search, Hydrus |
| `ACCESS_NETWORK_STATE` | Pause streaming downloads or warn on metered networks |
| `READ_MEDIA_VIDEO` (API 33+) | Video library screen |
| `READ_MEDIA_AUDIO` (API 33+) | Audio-only files (optional) |
| `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+) | Works with "Allow limited access" instead of failing |
| `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` | Library on Android 8–12 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (API 34+) | Keep playing audio in the background / screen off |
| `WAKE_LOCK` | Keep decoding while the screen is off during background audio |

Not used: `MANAGE_EXTERNAL_STORAGE`. Google Play rejects it for players. Folders come
from the Storage Access Framework (`OpenDocumentTree` with a persisted URI permission), and
single files from "Open with…" grants. `POST_NOTIFICATIONS` isn't needed because MediaSession
playback notifications are exempt; ask for it only if other notifications are added.

Manifest components:
- `MainActivity`:
  - `android:supportsPictureInPicture="true"`, `resizeableActivity`, and
    `configChanges` covering orientation, screen size and layout, so rotation doesn't restart playback.
  - Intent filters for `ACTION_VIEW` with:
    - mime `video/*`, `audio/*`, `application/x-matroska`,
      `application/x-subrip`, `text/x-ssa`;
    - schemes `content`, `file`, `http`, `https`.
  - `ACTION_SEND` for shared video links.
- `PlaybackService : MediaSessionService` with `foregroundServiceType="mediaPlayback"`, which provides
  lock-screen controls, the media notification, headset buttons, Bluetooth and Android Auto.
- Picture-in-picture: auto-enter on Home while playing (`setAutoEnterEnabled`, API 31+), with
  play/pause and skip as remote actions. Keep the aspect ratio set from the video.
- Audio focus: duck or pause for calls and other apps. Pause when headphones are unplugged
  (`AUDIO_BECOMING_NOISY`).

### Desktop

- **File associations** through Compose Desktop's `nativeDistributions.fileAssociation(...)` (**verify** the
  API on the Compose version in use). Register mkv, mp4, m4v, webm, avi, mov, ts, m2ts, wmv, flv, ogv,
  plus srt/ass/vtt (they open the player with the "load subtitle" picker).
  - Windows: registers under "Open with" and as a default-apps candidate. Windows needs the user to
    choose the default; apps can't set it themselves.
  - macOS: `CFBundleDocumentTypes` in the Info.plist. Files arrive through the `open-file` event.
  - Linux: `.desktop` entry with `MimeType=` lines.
- **Single instance:** a second launch passes its file to the running window over a local socket
  in the user's config dir, then exits.
- **macOS signing:** hardened runtime with `com.apple.security.cs.allow-jit`,
  `allow-unsigned-executable-memory` and `disable-library-validation`. The JVM and the bundled
  FFmpeg dylibs need all three. Notarize for Gatekeeper. If it's ever sandboxed for the App Store, add
  `com.apple.security.files.user-selected.read-only` and `network.client`.
- **Windows:**
  - Sign the installer, or SmartScreen warns on every download.
  - Per-user install like Stash, so no admin prompt.
  - Media keys through a global key hook. System media overlay (SMTC) integration is later and needs a small native helper.
- **Network:** outgoing only (streams, subtitles), so no firewall prompt.
- **Keep awake while playing:**
  - Windows: `SetThreadExecutionState`.
  - macOS: an IOPMAssertion.
  - Linux: the `org.freedesktop.ScreenSaver` inhibit call.

## 6. Features

Carried over from Stash's player:
- Controls:
  - seek bar with time preview,
  - ±5/10 s, speed, loop, volume,
  - borderless fullscreen and immersive mode,
  - auto-hiding controls,
  - every keyboard shortcut,
  - tap/double-tap gestures.
- Resume position, next/previous file.
- Subtitles:
  - formats: SRT, VTT, ASS/SSA, YouTube, zip;
  - loading: from file, link, or online search (YouTube, Jimaku, OpenSubtitles, SubDL, AniList);
  - adjustments: delay and size.
- Material 3 dark player theme.

New:
- **Tracks:** pick an embedded audio or subtitle track. Language preferences choose them automatically
  (e.g. Japanese audio + English subtitles).
- **Subtitle folder matching:** the same name with `.en.srt`/`.ass`, or a folder of files
  matched by episode number, loaded automatically. This would have covered the SumiSora + Fate case.
- **Chapters:** markers on the seek bar, and a "Skip intro/ending" button when chapters are named OP/ED.
- **Playlist / folder mode:** open a folder and play the episodes in natural order (E2 before E10). Auto-play the next one with a 5 s countdown.
- **Library:** recent files and continue watching. On Android, also the MediaStore video list.
- **Precise controls:** frame step (`,` `.`), A–B loop, audio delay, screenshot (`S`, to Pictures).
- **Gestures (Android):**
  - horizontal swipe seeks,
  - right-edge vertical swipe changes volume,
  - left-edge vertical swipe changes brightness,
  - pinch zooms to fill.
- **Hydrus as a source:** a Hydrus page or search becomes a playlist. Stash can also hand a file to the
  Midas app (intent on Android, command line on desktop) when the user picks "Open in Midas".

Later: HDR → SDR tone mapping, bitmap subtitles (PGS), SMTC on Windows, casting.

## 7. Milestones

Each milestone ends with a build that's installed and tested on real files. The test set:

| File | Checks |
|---|---|
| Fate/stay night E01 (H.264 1080p MP4, `mov_text` English) | Embedded subtitle track listed and in sync |
| An MKV with styled ASS and fonts attached (any fansub release) | Track selection, then libass styling |
| 10-bit HEVC MKV | Hardware decode on Windows without the HEVC extension |
| AV1 WebM (e.g. a YouTube download) | Software/hardware AV1 |
| A file with chapters + multiple audio languages | Chapters, audio switching |

1. **Engine split:** move the player into `player-core`/`player-ui` behind `PlaybackEngine`, still on
   ComposeMediaPlayer. Stash works exactly as before. This makes the rest safe to build gradually.
2. **FFmpeg desktop engine:**
   - play, pause, seek, A/V sync, hardware decode,
   - embedded audio and subtitle tracks.
   - Stash desktop switches to it.
   - Done when: all files above play, stay in sync within 40 ms after 20 random seeks, and 4K plays smoothly.
3. **Standalone desktop app:** file associations, single instance, folder playlists, subtitle
   folder matching, chapters, library of recent files. Signed Windows installer.
4. **Android app:** deliver the MVP, Base and Polish stages described below.
5. **Remaining cross-platform polish:** HDR tone mapping, Windows SMTC and casting. Desktop libass,
   A–B loop and screenshots are already implemented; Android equivalents are covered below.

### Android delivery: MVP → Base → Polish

Each stage builds on the preceding one and ends with an installable build tested on a physical
Android device. The app keeps the `app.auriel.midas` application ID from the first build.

#### 1. MVP — open and play a video

Implementation is present in `player-android` and `midasApp-android`. An Android debug APK builds;
physical-device acceptance of the flows below remains outstanding.

- Create `player-android` and `midasApp-android`, connect the shared player interface and UI to a
  Media3 engine, and provide a basic Android launcher and player screen.
- Open a video selected with the system file picker or received from Files through an `ACTION_VIEW`
  content URI. Preserve the URI grant for as long as needed, and handle missing or revoked access.
- Support play/pause, seek, volume, fullscreen, rotation without losing playback, and selection of
  embedded audio and text subtitle tracks supported by Media3. Surface playback errors clearly.
- Support an HTTP(S) video URL with optional headers, basic subtitle loading, and resume position.
  Declare only the permissions and intent filters needed by these flows.
- **Done when:** a clean install opens a local video from both the picker and Files, plays a direct
  video URL, switches tracks, resumes after leaving playback, and survives rotation on a real device.

#### 2. Base — a usable everyday player

- Add the MediaStore video library with version-appropriate media permissions and limited-access
  handling. Add folder browsing through the Storage Access Framework with persisted grants, natural
  ordering, playlist navigation and optional next-video autoplay.
- Add recent and continue-watching views, subtitle folder matching, language preferences, chapters,
  and the shared subtitle search and style controls where supported on Android.
- Add a `MediaSessionService` for background audio, media notification, lock-screen, headset and
  Bluetooth controls. Handle audio focus, calls and headphone unplug events.
- Add picture-in-picture with playback actions and the video's aspect ratio. Complete the relevant
  Android permissions, intent filters and lifecycle handling from section 5.
- Integrate the Media3 FFmpeg decoder extension and ABI splits; verify the supported codec list and
  test hardware and software fallback on devices that need it. Connect Stash's Android “Open in
  Midas” action when the Stash integration is available.
- **Done when:** a user can browse their library or an authorized folder, choose and play an episode,
  switch tracks, continue in the background or PiP, and get the expected pause/duck behavior for
  calls and unplugged headphones. Test both full and limited media access on applicable Android versions.

#### 3. Polish — advanced playback and release

- Add Android ASS/SSA rendering with libass, including attached fonts and a graceful fallback if it
  cannot be loaded. Verify compatibility with the Media3 version in use.
- Add horizontal seek, edge volume and brightness gestures, pinch-to-fill, A–B loop, frame step and
  screenshots where the platform and decoder permit them. Refine touch controls and accessibility.
- Test the milestone media set, low-memory and lifecycle recovery, background playback, rotation,
  PiP and codec fallback across supported Android versions and ABIs; address performance and battery use.
- Prepare Play and GitHub builds: ABI packaging, shared signing-key strategy, privacy policy, Data
  safety declaration, release notes and checksums. Verify current Play requirements, third-party
  licence notices, source distribution and patent considerations before publishing.
- **Done when:** the advanced features and release builds pass the device test matrix, and the Play
  and GitHub artifacts are ready for review and distribution.

## 8. Size and licences

- **Size:**
  - Desktop installer: about 80 MB today, roughly +40 MB per OS for FFmpeg.
  - Android: FFmpeg extension adds about 5–10 MB per ABI with a decoder-only build.
- **Licences:**
  - FFmpeg LGPL build, linked dynamically, with its licence and source offer shipped in About.
  - libass is ISC; Media3 is Apache 2.0.
  - Avoid GPL-only parts (x264 etc.) since they're encoder-side and unneeded.
- **Patents:** HEVC/AAC decoding can carry patent obligations in some countries for distributed
  software. Since Midas goes on Google Play, check this before the first public release.
  On Android, hardware decoding through the phone's own codecs avoids most of it, because the
  phone maker holds those licences.

## 9. Distribution (Google Play + GitHub)

- **One signing key for both.**
  - Enrol in Play App Signing using **your own app signing key**, not one Google generates.
    Sign the GitHub APKs with that same key.
  - Users can then move between the Play and GitHub versions without uninstalling.
    With different keys, Android refuses to update one with the other.
  - Back the key up as with Stash's keystore.
- **Google Play:**
  - Upload an `.aab`; Play splits it per ABI itself.
  - Target the current required `targetSdk`.
  - A privacy policy URL is required, because the app uses the network and reads media.
  - Fill in the Data safety form. Midas collects nothing, but subtitle searches send titles to the
    providers, so declare that.
  - Justify `FOREGROUND_SERVICE_MEDIA_PLAYBACK` with a short video of background playback.
  - Section 5's permissions avoid the ones Play restricts (`MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`).
- **GitHub releases:**
  - Per-ABI APKs plus one universal APK.
  - Windows `.exe`/`.msi`, macOS `.dmg`, Linux `.deb`/AppImage.
  - Release notes and SHA-256 checksums.
  - **Update check:** the GitHub build checks the latest release on startup and links to it.
    The Play build doesn't, because Play handles updates. This is a build flag.

## 10. Stash and Midas together

- Stash keeps its built-in player, built from `player-ui` with the same engines. Player fixes reach both apps.
- **"Open in Midas"** in Stash's player menu and the grid's context menu:
  - Android: an `ACTION_VIEW` intent to `app.auriel.midas` with the stream URL and a
    `Hydrus-Client-API-Access-Key` header extra.
  - Desktop: launch `Midas.exe` with the URL and a `--header` argument.
  - Hidden when Midas isn't installed.
- Resume positions stay separate per app at first. They could be shared later.

## 11. Open questions

1. macOS: worth paying for an Apple developer account to sign and notarize?
2. App licence: GPLv2. Verify dependency notices and source distribution requirements before release.
3. App icon and branding for Midas (gold, given the name?).

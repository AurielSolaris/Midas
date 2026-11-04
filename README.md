# Midas

The project includes the [GPLv2 licence](LICENSE). Third-party distribution notices and source information still need to be completed.

A video player with an FFmpeg desktop engine and an initial Media3 Android app. The desktop player
supports embedded tracks and GPU decoding. The roadmap is in [GOALS.MD](GOALS.MD).

## Run

```
run_midas.bat --dev              build and run from source
run_midas.bat --dev "D:\Anime\Show\05.mkv"
gradlew :midasApp-desktop:run
gradlew :midasApp-desktop:packageMsi    Windows installer -> midasApp-desktop/build/compose/binaries/main/msi/
gradlew :midasApp-android:assembleDebug Android debug APK -> midasApp-android/build/outputs/apk/debug/
```

Install without prompts (per user, into %LOCALAPPDATA%/Midas, no admin rights):

```
msiexec /i midasApp-desktop/build/compose/binaries/main/msi/Midas-1.0.3.msi /qn
```

A newer MSI upgrades the installed one in place (fixed upgrade code). Uninstall from Windows Settings → Apps.

### libass (ASS subtitles)

Full ASS rendering uses libass 0.17 and its libraries (FreeType, HarfBuzz, FriBidi, Fontconfig, GLib…),
prebuilt for Windows x64 by MSYS2. They live in `midasApp-desktop/appResources/windows-x64/libass/`
(with their licences) and ship inside the installer. To refresh them:

```
pip install zstandard
python tools/fetch_libass.py
```

Without libass, ASS subtitles still show, as plain text in your subtitle style.

### Android MVP

Build the debug APK with Android SDK platform 37 installed and `ANDROID_HOME` set to the SDK path,
or configure the SDK in Android Studio. Install the generated APK on a device or emulator with
`adb install -r midasApp-android/build/outputs/apk/debug/midasApp-android-debug.apk`.

The Android app uses Media3 and the shared player UI. It offers the system video picker, opening a
video through Android's **Open with** flow, direct HTTP(S) links with optional headers, playback and
seek controls, embedded audio and text track selection, external subtitle loading, and saved playback
positions. The picker requests a persistent URI grant where the provider allows it. Android playback
uses the codecs available through Media3 and the device; the FFmpeg extension, background audio, PiP,
library browsing and full ASS styling are later stages. YouTube watch links are currently supported
only by the desktop resolver.

The Android APK builds and has been exercised on an emulator; the full MVP acceptance checklist,
including real-device playback, access grants and rotation, still needs validation.

Arguments are files, folders or URLs; `--header "Name: value"` adds an HTTP header (for Stash's
"Open in Midas" with the Hydrus access key). A second launch hands its file to the open window.
Files and folders can also be dropped on the window.

### YouTube links

Choose **Open link** and paste a YouTube watch, short or share link. Midas resolves the link in the
background and keeps the original link in the recent list, so reopening it resolves a fresh stream.
The player settings button groups **Playback speed** and **Resolution**; local files play at their
original resolution.

YouTube playback requires `yt-dlp` and a supported JavaScript runtime such as Node.js or Deno on
`PATH`. They are not bundled with the installer. For example, with Python installed:

```
python -m pip install -U yt-dlp
yt-dlp --version
node --version
```

Install Node.js separately if `node` is unavailable. Midas allows yt-dlp to fetch its JavaScript
challenge components when needed, so resolution requires network access. Keep yt-dlp and the runtime
up to date if YouTube playback stops working. See the [yt-dlp JavaScript setup guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS).

Only combined video-and-audio streams are supported. The resolution menu lists those streams, which
may be limited to 360p even when YouTube offers higher-resolution video and audio separately.
Availability can vary by video and over time.

## Icon

`midasApp-desktop/icons/icon.svg` is the source. After editing it, run `python icons/render_icon.py` from
`midasApp-desktop/` (needs `cairosvg` and Pillow) to regenerate `icon.png`, `icon.ico` and `icon.icns`.

## Modules

| Module | What |
|---|---|
| `player-core` | `PlaybackEngine` interface, tracks/chapters model, subtitle parsers (SRT, VTT, ASS, YouTube), online subtitle search, settings, natural sort and subtitle-file matching. KMP, no UI. |
| `player-ffmpeg` | Desktop engine: FFmpeg 8.1.2 (LGPL build) through JavaCPP 1.5.14. Demux, video and audio threads; audio is the master clock; D3D11VA / VideoToolbox / VAAPI with software fallback. libass through JNA for ASS. Clip export. |
| `player-ui` | The player screen, menus, subtitle finder, and the four themes (Light, Dark, Halloween, Gold). KMP Compose. |
| `midasApp-desktop` | Window, library, saved folders and playlists, thumbnails, YouTube link resolution, settings, single instance, file associations. |
| `player-android` | Media3 implementation of the shared playback engine. |
| `midasApp-android` | Android launcher, file and link opening, URI access and settings. |

## Player keys

Space/K play · ←/→ 5 s (Shift: 1 s) · J/L 10 s · ↑/↓ or wheel volume · M mute · F or double-click fullscreen ·
C subtitles on/off · V next subtitle track · T next audio track · Z/X subtitle delay · G/H audio delay ·
−/= subtitle size · [ ] speed · , . frame step · S screenshot (Pictures\Midas) · A A–B loop · E export clip · W fill/fit ·
0–9 jump · N/P next/previous file · Home/End · Esc close.

## What works

- Plays from files, folders (choose a video from a naturally sorted list; the next one auto-plays after a 5 s countdown) and HTTP(S) URLs with headers.
- YouTube watch links use an installed `yt-dlp` on PATH and a supported JavaScript runtime (Node.js or Deno). Playback settings offer the combined audio/video resolutions YouTube makes available; these can be lower than YouTube's highest separate video streams.
- Save folders in the library, or create named playlists and add videos individually. Folder lists refresh from disk; playlists retain their selected files. Local videos show cached thumbnails in lists and recent items.
- Embedded audio tracks (switchable) and embedded subtitle tracks, chosen by language preference
  (e.g. Japanese audio → English subtitles; the file's own track wins over a loose .srt):
  text (SRT, ASS, mov_text, WebVTT…) and picture subtitles (Blu-ray PGS, DVD VobSub, DVB), drawn on the picture.
- Subtitle files next to the video (`Show 05.en.ass`), in `Subs/` folders, or in a chosen folder matched by episode number.
- Online subtitle search (YouTube, Jimaku, OpenSubtitles, SubDL, AniList), delay and size.
- **Full ASS/SSA styling** through libass, for the file's own ASS tracks and loaded .ass files: the script's fonts
  (including fonts attached to MKVs), colors, outlines, positions, rotation, karaoke, drawings and animations.
- **Subtitle style** (Settings → Subtitle style, or the player's subtitle menu → Style), with a live preview:
  font face (any installed font), size, bold, text color, outline color and thickness, and a background box
  with its color and opacity. It applies to all plain subtitles (SRT, VTT, mov_text, YouTube…); turn on
  "Use this style for ASS subtitles too" to replace ASS scripts' own styles as well (size always applies).
- Chapters on the seek bar and "Skip intro / ending" for OP/ED chapters.
- Resume positions, recent files, speed (pitch-corrected), loop, A–B loop, frame step, screenshots, audio delay.
- Keeps the screen awake while playing.
- **Hardware or software, your choice** (Settings → Playback):
  - *Video decoding*: Hardware (D3D11VA / VideoToolbox / VAAPI, dropping to software for formats the GPU can't take) or Software.
    The player's header shows which one is in use.
  - *Video encoding*, for **clip export** (A at the start, A at the end, then E or "Export clip"): Hardware tries
    NVENC → Quick Sync → AMF → Media Foundation (VideoToolbox on macOS) and uses the first that works; Software uses
    OpenH264. Clips go to Videos\Midas as H.264 MP4 with the current audio track copied.

Tested headless by `EngineSmokeTest` (set `MIDAS_TEST_FILE` to a video): sync within a frame after play and seek,
audio switching, pause, frame step, end of file, software decoding, and clip export with both encoders.
`RealFileTest` (`MIDAS_REAL_FILE`) checks a real episode's embedded subtitles and sync after seeks;
`BitmapSubtitleTest` (`MIDAS_PGS_FILE`) checks picture subtitles; `LibassTest` (`MIDAS_ASS_FILE`) renders a styled
ASS track with an attached font, with and without the style override.
`YouTubePlaybackTest` checks stream resolution, video decoding and the presence of an audio track when
`MIDAS_YOUTUBE_TEST_URL` is set to a YouTube video URL; it requires network access, yt-dlp and a JavaScript runtime.

## Not yet

- HDR → SDR tone mapping, media keys outside the window, SMTC.
- YouTube playback using separate video and audio streams for higher resolutions.
- macOS signing/notarization; Windows installer signing.
- Android Base and Polish features in the [roadmap](GOALS.MD#android-delivery-mvp--base--polish), and moving Stash onto `player-ui`.

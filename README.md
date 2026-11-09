[![CI](https://github.com/demongroker/video-droid/actions/workflows/ci.yml/badge.svg)](https://github.com/demongroker/video-droid/actions/workflows/ci.yml)

# VideoDroid

Phone-only Android video downloader. Paste or share a link, pick a quality, tap **Download**.
yt-dlp and FFmpeg run on the device. No fetch farm, no account.

Current release: **2.3.5** (versionCode 91).

## Screens and controls

Keep this map current whenever a screen, control or gesture changes.

| Screen | How to reach it | Main controls |
|---|---|---|
| **Download** | app icon, or the Download tab | Link field + paste button, **Download**, quality chips (4K / 1080 / 720 / 480), one **Options** button, a sideways-scrolling row of social preset chips, gear icon → Settings. A progress card with **Cancel current** appears only while a job is live. |
| **Options sheet** | **Options** on the Download tab | Re-encode on device, aspect (Fill / Fit / Original — greyed out until re-encode is on), ratio, trim. This is the only place these exist. |
| **Queue** | bottom bar | Every job with a thumbnail, its name, state · quality · live speed and ETA, a progress bar, and pause / resume / cancel. Failed rows get **Retry**. Empty state offers **Go to Download**. |
| **Library** | bottom bar | Storage bar, **Find duplicates**, **Sort** (newest / largest / name), thumbnail grid with size and duration badges. Tap to play, long-press to select several: Share, Move, Delete. Optional fingerprint/PIN lock. |
| **Settings** | gear on the Download tab | Simultaneous downloads, Battery, Log in to X, Clear login, Local video, My last link, Recommended settings, Lock the Library, Hide from Gallery, Update download engine, Copy log, Check update, Changelog. |
| **Share sheet** | share a link from any app to VideoDroid | Queues immediately with the last preset. Any `text/*` share is accepted. |
| **Clipboard banner** | open the app with a video link copied | "Download this?" with **Download** / **✕**. Never starts on its own. |
| **Open page** | shown after a failed job | In-app WebView so a direct media URL can be picked. |
| **Login X** | Settings → Log in to X | Signs into X in a WebView; cookies stay on the phone for authenticated downloads. |

Theme: `Theme.Material3.DayNight.NoActionBar` — black background (`#000000`) with a purple
accent (`#8B5CF6`, soft `#A78BFA`), emerald `#34D399` for storage, `#F87171` for destructive
actions. No dynamic colour. Every screen calls `Insets.applyTo()` so content stays out of the
system bars.

## How to use

Recommended: **1080p** + Original + trim **Off**.

- Quality is **4K / 1080 / 720 / 480** (1080 is the default). Every social preset uses 1080.
- **Re-encode off:** the original file is saved untouched, FFmpeg is skipped, and aspect is
  greyed out (it would do nothing).
- **Re-encode on:** choose **Original**, **Fill** (crop) or **Fit** (pad), plus an optional
  trim window. Encoding is `h264_mediacodec` at 16M / 24M (≥1080) with **H.264 + AAC**; if the
  hardware encoder rejects the source, a **mpeg4** software fallback still produces a playable
  file (plays on the phone, not X-uploadable).
- Fill/Fit remuxes to clean MP4 first when the container is not already one; if decode fails it
  remuxes and tries again.
- Failures show a short reason; the last job URL is persisted so **Open page** survives process
  death. Site TLS problems say **Site TLS bad. Open page.**

Up to **5 downloads at a time** (Settings → Simultaneous downloads, default 3). Pausing keeps
the partial file, so a resume continues where it stopped on servers that support ranges. If the
queue file is ever corrupt, a timestamped backup is kept (`download_queue.json.bad.<ts>`) and the
queue starts empty.

### Social presets

One sideways-scrolling row. Each tap overrides all settings and persists them.

| Preset | Settings |
|---|---|
| X | Re-encode on, 1080p, Fit, Portrait 9:16 |
| TikTok / Shorts / Reels | Re-encode on, 1080p, Fill, Portrait 9:16 |
| YouTube | Re-encode on, 1080p, Original |
| Instagram | Re-encode on, 1080p, Fill, 4:5 |
| Facebook | Re-encode on, 1080p, Fit, Landscape 16:9 |

## Duplicate blocking

Two independent gates, both applied **before** anything is written to the library:

1. **Link identity** — the URL is normalised (host lowercased, tracking parameters dropped,
   trailing slash ignored; X posts compared by status id) and checked against the queue and the
   library.
2. **Content identity** — the finished file is hashed with SHA-256 and compared against
   `saved_hashes.json`, so the same bytes under a different name, from a mirror or a re-upload,
   are recognised too.

Nothing is ever deleted to resolve a duplicate. Extra copies are **moved** to
`Downloads/VideoDroid/duplicates` (the Library and its duplicate scan ignore that folder), and a
job that would duplicate an existing file is skipped with **Already in library — skipped**.

Scope, honestly: `saved_hashes.json` only knows files saved since 2.3.4 plus whatever the
one-time backfill has hashed so far. Older files are still matched by link and by name.

## File naming

`<title>_<date>_<quality>.<ext>` — for example `Bambi_Brooks_20261109_1080p.mp4`.

- X posts use the date encoded in the status snowflake, and keep the short status id before the
  quality so the duplicate gate has a stable anchor.
- When the extractor returns no usable title the name falls back to `video_<link-tag>_…`, which
  is still unique per link.
- A collision never overwrites: the second copy becomes `…_2.mp4`, the third `…_3.mp4`, up to
  `_99`, then a timestamp.
- Aspect is deliberately not part of the name.

## Download engine self-update

The bundled yt-dlp is frozen inside the APK, so a site change would otherwise need a new build.

- A **weekly** job (unmetered networks only) fetches the latest pure-Python wheel from PyPI and
  unpacks `yt_dlp/` into the app's files directory; `dl.py` puts that directory ahead of the
  bundled copy on `sys.path`.
- The wheel's **SHA-256 is verified against PyPI's own published digest** first. A mismatch is
  discarded, the current engine is kept, and the result is written to the job log.
- **Settings → Update download engine** runs the same check on demand and reports the version.
- To fall back to the bundled engine, delete the app's `files/engine` folder (or reinstall).

## Privacy

- **FLAG_SECURE is on for every screen.** Nothing appears in screenshots, screen recordings or
  the system Recents thumbnail. This also blocks your own screenshots of the app.
- **Library lock** (Settings): fingerprint, or the phone's PIN / pattern / password as fallback,
  before the grid is shown. A phone with no screen lock is told to set one rather than being
  locked out of its own library.
- **Hide from Gallery** (Settings): writes a `.nomedia` marker into `Download/VideoDroid`.
  Caveat: this hides the folder from the media scanner and most gallery apps, but rows that were
  inserted through MediaStore on Android 10+ can still be listed by a determined gallery app.
- Cookies stay on the phone. Nothing is uploaded anywhere.
- **Crash logs stay on the phone too.** An uncaught exception or a failed worker is written to
  `filesDir/crashes`, readable in **Settings → Crash logs** with Copy and Share; the last 200
  log lines are attached and URL query strings / tokens are stripped before anything is saved.

## Updates

**Settings → Check update** asks GitHub `releases/latest`. No token ships in the app. While a job
is running the result is a Toast so it cannot wipe download status. Install is fail-closed: the
APK must carry the same signing certificate as the installed app.

## Build

JDK 17, Android SDK 36. Do not commit keystores, `gradle.properties` or APKs.

```bash
./build.sh                 # release -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug    # debug   -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Release builds are R8-minified and `debuggable=false`. Both variants are signed with the release
key so either can be installed over an older version in place.

## Limits

- No `curl_cffi` on device; some Cloudflare-protected sites answer 403. Use **Open page**.
- Unsupported sites fail with a plain "no extractor" message.
- Queue thumbnails come from the page's own preview image; a JavaScript-only page may show an
  empty tile instead.
- The hash index covers files saved since 2.3.4, plus what the background backfill has reached.

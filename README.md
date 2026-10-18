# VideoDroid

Phone-only Android video downloader. Paste or share a link, pick quality, tap
Download. yt-dlp and FFmpeg run on the device. No fetch farm, no account.

Current release: **1.6.28** (versionCode 55).

## Screens and controls

Keep this map current whenever a screen, control or gesture changes.

| Screen | How to reach it | Main controls |
|---|---|---|
| **Download** | app icon, or the Download tab | Link field, quality spinner (4K/1080p/720p/480p), Local video, Export switch, More section (Library, Changelog, Copy log, Check update, Login X, Clear login), social preset chips, Recommended / My last, Go, Cancel current, status + progress card |
| **Queue** | bottom bar | Pending jobs (head first), Clear (empties the queue and cancels the run) |
| **Library** | bottom bar, or More → Library | Finished downloads from `Download/VideoDroid`, newest first; tap a row to play (falls back to a share chooser) |
| **Share sheet** | share a link from any app to VideoDroid | Quality toggle (4K/1080p/720p/480p), convert-for-social switch, Download / Cancel |
| **Open page** | "Open page" after a failed job | Loads the page in a WebView so a direct media URL can be picked |
| **Login X** | More → Login X | Signs into X inside a WebView; cookies are kept for authenticated downloads |

Theme: `Theme.Material3.DayNight.NoActionBar`, dynamic colour on Android 12+
(`DynamicColors.applyToActivitiesIfAvailable`), indigo `#3949AB` fallback,
night palette in `values-night/`. Every screen calls `Insets.applyTo()` so
content stays out of the system bars.

## How to use

Recommended: **1080p** + **Original** + trim **Off**.

- Quality is **4K / 1080p / 720p / 480p** (1080p is the default). The old
  "best" option is gone; social presets all default to 1080p.
- **Export Off:** save the original file only. Fill / Fit / ratio / trim are
  hidden. FFmpeg is skipped.
- **Export On:** encode with **Fill** (crop) or **Fit** (pad). Trim is only
  available when Export is On (defaults 1s / 1s).
- Encode quality: h264_mediacodec **16M** / **24M** (≥1080), bufsize 2×
  (32M / 48M). **H.264 + AAC** for X-uploadable output; if h264_mediacodec
  rejects a source, a 4th **mpeg4** software fallback still produces a
  playable MP4 (plays on the phone, but is not X-uploadable).
- Fill/Fit remuxes to MP4 first when the container is not clean MP4; if decode
  fails, remux then pad/crop again.
- Failures show a short reason plus **Open page** (in-app WebView; last job
  URL is persisted so it survives process death). Site TLS / certificate
  errors say **Site TLS bad. Open page.**

Queue is FIFO with one active job. You can enqueue while something is
downloading; the button says **Add to queue** when a job is running or
waiting. If the queue file is corrupt, a timestamped backup is kept
(`download_queue.json.bad.<ts>`) and the queue starts empty.

**More** holds Login X, Open page adblock, Check update, Changelog, and social
presets. Each preset tap overrides all settings and persists:

| Preset | Settings |
|---|---|
| X | Export On, 1080p, Fit, Portrait 9:16, trim Off |
| TikTok / Shorts / Reels | Fill 9:16 |
| YouTube | Original |
| Instagram | Fill 4:5 |
| Facebook | Fit 16:9 |

Changelog is also at `app/src/main/assets/CHANGELOG.md`.

## Updates

Check update (More) tries GitHub `releases/latest` first, then Tailscale
`:8899`. No token in the app. While a job is running, update results are Toast
only so they do not wipe download status. Install is fail-closed: the APK
must share the same signing certificate as the installed app.

## Build

JDK 17. Do not commit keystores, `gradle.properties`, or APKs.

```bash
./build.sh            # -> app/build/outputs/apk/release/app-release.apk
```

## Limits

- No `curl_cffi` on device; some Cloudflare sites 403. Use Open page.
- Cookies stay on the phone (Login X).

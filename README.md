# VideoDroid

Phone-only Android video downloader. Paste or share a link, pick quality, tap
Download. yt-dlp and FFmpeg run on the device. No fetch farm, no account.

Current release: **1.6.1**.

## How to use

Recommended: **best** + **Original** + trim **Off**.

- **best** is 1080-capped. There is a separate **4K** option. 1080 / 720 / 480
  stay available.
- **Export Off:** save the original file only. Fill / Fit / ratio / trim are
  hidden. FFmpeg is skipped.
- **Export On:** encode with **Fill** (crop) or **Fit** (pad). There is no
  separate export-height control. Trim is only available when Export is On
  (defaults 1s / 1s).

Queue is FIFO with one active job. You can enqueue while something is
downloading. Failures show a short reason plus **Open page** (in-app WebView
with optional adblock).

**More** holds Login X, Open page adblock, Check update, Changelog, and social
presets. Each preset tap overrides all settings and persists:

| Preset | Settings |
|---|---|
| X | Export On, best, Fit, Portrait 9:16, trim Off |
| TikTok / Shorts / Reels | Fill 9:16 |
| YouTube | Original |
| Instagram | Fill 4:5 |
| Facebook | Fit 16:9 |

Changelog is also at `app/src/main/assets/CHANGELOG.md`.

## Updates

Check update (More) tries GitHub `releases/latest` first, then Tailscale
`:8899`. No token in the app. While a job is running, update results are Toast
only so they do not wipe download status.

## Build

JDK 17. Do not commit keystores, `gradle.properties`, or APKs.

```bash
./build.sh            # -> app/build/outputs/apk/release/app-release.apk
```

## Limits

- No `curl_cffi` on device; some Cloudflare sites 403. Use Open page.
- Cookies stay on the phone (Login X).

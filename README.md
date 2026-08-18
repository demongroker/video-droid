# VideoDroid

On-device Android video downloader + auto-formatter. Paste or share any video
link, choose your settings, and it downloads with yt-dlp, trims the ends,
crops/pads to your aspect ratio, and exports at your chosen quality — then
saves the finished MP4 straight to your phone's Downloads.

Fully offline: yt-dlp (via Chaquopy Python) and FFmpeg are bundled inside the
APK. No server, no account.

## Features
- Download any link yt-dlp supports (YouTube, direct MP4, etc.)
- Menu settings:
  - Download quality: best / 1080p / 720p / 480p
  - Export height: 720p / 480p / 1080p
  - Aspect ratio: original, crop (4:3, 16:9, 1:1), pad (4:3, 16:9, 9:16)
  - Auto-trim start / end (seconds)
- Saves to `Downloads/VideoDroid/`
- Share a link into the app to prefill it

## Build
Requires Android SDK 34, JDK 17, Gradle 8.9, and a Python 3.11 for Chaquopy.

```bash
./build.sh            # -> app/build/outputs/apk/release/app-release.apk
```

Signing is configured via `signingConfigs.release`; the keystore and
`gradle.properties` (which hold the signing passwords) are gitignored — supply
your own keystore to build a signed APK.

## Notes / limitations
- On-device builds can't ship `curl_cffi`, so Cloudflare-challenged sites may
  403. YouTube and most standard sites work fine.
- Telegram bots cap uploads at 50MB — this app avoids that entirely since the
  file is saved directly on the phone.

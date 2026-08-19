# VideoDroid changelog

Private phone app. Updates: public GitHub Releases (no token in the APK).

## 1.6.26 (versionCode 53)

- **"best" removed**: quality dropdown is now 4K / 1080p / 720p / 480p only. All social presets (X, TikTok, Shorts, Reels, YouTube, Instagram, Facebook) and Recommended default to **1080p**.

## 1.6.25 (versionCode 52)

- **Export H.264-REQUIRED**: Export downloads now use AVC-only format (`bv*[vcodec^=avc1][height<=1080]+ba/...`) with **no any-codec fallback**. If the video is HEVC-only, the download fails fast with a clear message instead of grabbing 676 MB of undecodable HEVC. Export-OFF and 1080/720/480 presets unchanged.

## 1.6.24 (versionCode 51)

- Fix convert producing an empty 261-byte file with rc:0 on HEVC sources (hardware decode emitted 0 frames). The encode now **validates the output** (≥1024 bytes) and, on empty/failed output, retries in order: ① hardware decode, ② software decode (no hwaccel) + h264_mediacodec, ③ software decode + `-vf format=yuv420p`. If all attempts fail, the job fails with a clear reason ("Could not decode this video on this phone. Try quality 1080 (H.264) instead of best (HEVC).") and keeps the original download — never a fake success.
- Recommended `best` and the X preset now **prefer H.264 (avc1) sources** (`bv*[vcodec^=avc1][height<=1080]+ba/...`) so HEVC decode problems are avoided at download time. 1080/720/480 presets unchanged.

## 1.6.22 (versionCode 49)

- Fix HEVC/H.265 input: encode now uses **hardware decode** (`-hwaccel mediacodec -hwaccel_output_format nv12`) before the input file, so h264_mediacodec no longer fails with "Invalid data found when processing input" on H.265 downloads. Encode path outputs NV12 (`-pix_fmt nv12`) for the MediaCodec encoder. Copy/remux path untouched, still pure stream copy.

## 1.6.21 (versionCode 48)

- Fix 1.6.20 regression: convert never started after download. Removed re-export feature (persistSource/lastSourceOrNull) which caused the encode path to stall. Local video picker and H.264-only encode unaffected.

## 1.6.20 (versionCode 47)

- X-safe export: Export On now encodes **H.264 (h264_mediacodec) + AAC only**. No mpeg4 fallback (X rejects MPEG-4 Part 2); if the MediaCodec encoder fails the job fails with a short reason and keeps the original.
- Encode bitrate back to 16M / 24M (≥1080), bufsize 2× (32M / 48M). No libx264, no forced fps.
- Re-export last download: change Export / Fit / X preset and run convert again — yt-dlp is skipped when the last source file still exists.
- **Export local video…**: SAF file picker (Files/Gallery) — pick any video on the phone and run the same H.264 export, no URL needed.
- Persist last input + output path for re-export.

## 1.6.19 (versionCode 46)

- Clamp trim start/end prefs to 0–86400s (fallback last-good / 1s). Corrupt or huge saved values no longer feed insane FFmpeg `-ss`/`-t`. Applied on save, queue JSON, and service extras.

## 1.6.18 (versionCode 45)

- MIT LICENSE at repo root.
- Unknown or missing saved quality / aspect / trim-related spinner values fall back to Recommended (best / Original) instead of crashing or leaving a blank selection.

## 1.6.17 (versionCode 44)

- Export On shows Fill/Fit, ratio, and auto-trim on the main page (no More). Export Off still hides them and locks Original.
- More stays Login X, Changelog, Check update, Copy log. Social presets and progress bar unchanged.

## 1.6.16 (versionCode 43)

- Material compact UI, honest linear progress bar, social presets expand/collapse at the top.
- Tighter vertical spacing so more of the one-page menu sits higher (48dp Download target kept).

## 1.6.15 (versionCode 42)

- README catch-up: current product (export, encode quality, remux, Open page / TLS, queue backup, fail-closed install).
- Download button says **Add to queue** when busy or the queue is not empty.

## 1.6.14 (versionCode 41)

- Encode quality bump: h264_mediacodec 20M / 30M (≥1080), bufsize 2×; mpeg4 `-q:v 3`.

## 1.6.13 (versionCode 40)

- Queue file corrupt: keep a timestamped backup (`download_queue.json.bad.<ts>`), show `Queue file corrupt. Backup kept.`, and start empty instead of silently dropping the file.

## 1.6.12 (versionCode 39)

- Cancel targets the active job only: FFmpegKit.cancel(sessionId) for the running convert, not a global cancel that can kill the next queued job.

## 1.6.11 (versionCode 38)

- Fit 3:4 (and other Fill/Fit) on the phone: remux to MP4 first when the container is not clean MP4; if decode fails (`Invalid data` / AVERROR 69), remux then pad/crop again. Copy remux, then video-copy + AAC if needed.

## 1.6.10 (versionCode 37)

- Open page WebView: no mixed content, http/https navigation only, JS bridge removed. Keep last-job URL clear on success.

## 1.6.9 (versionCode 36)

- Updater: fail-closed same-signing-certificate check before install.

## 1.6.8 (versionCode 35)

- Open page loads last job URL (persisted; survives process death).

## 1.6.7 (versionCode 34)

- Convert/pad/encode fail: keep the yt-dlp file (save to Downloads) and say so. Do not delete the successful download.

## 1.6.6 (versionCode 33)

- Fit pad never smaller than frame (3:4 720p)

## 1.6.5 (versionCode 32)

- TLS / certificate / hostname mismatch / CERTIFICATE_VERIFY_FAILED: short UI — Site TLS bad. Open page. Not No network.

## 1.6.4 (versionCode 31)

- YouTube (and similar) `Requested format is not available` on **best**: short UI reason — that format is gone; try 1080 or Open page. Not a generic dump.

## 1.6.3 (versionCode 30)

- Cancel aborts extract_info / urlopen / X GraphQL promptly (not only download hooks). Socket timeout remains the hang upper bound.

## 1.6.2 (versionCode 29)

- Honest download status when size is unknown (HLS): show `Downloading…` / bytes, never a fake stuck 1%

## 1.6.1 (versionCode 28)

- **best** is 1080-capped (`bv*[height<=1080]+ba/b[height<=1080]/bv*[height<=1080]/b`). Separate **4K** option.
- Export Off = original file only: hide Fill/Fit/ratio/trim; job forced Original + trim off
- Check update: never overwrite job status; Toast only while queue/busy. GitHub then Tailscale `:8899`; HTTP error if both fail
- Encode: h264_mediacodec `-b:v` 16M / 24M (≥1080), bufsize 2×. mpeg4 `-q:v 4`. No libx264, no forced 30fps
- More → Changelog
- Social presets override all settings (X / TikTok / YouTube / Instagram / Facebook)

## 1.6.0 (versionCode 27)

- Fill/Fit + grouped ratios; short-title filenames

## 1.5.9 (versionCode 26)

- Job status quality/aspect/trim; Check update Tailscale fallback

## 1.5.8 (versionCode 25)

- Open page: Adblock On/Off (persisted). Compact host list; never block page host or media (m3u8/mp4/webm/ts/mpd, video.twimg, googlevideo)
- Open page: JS sniff after play (video src/currentSrc, media src hook, performance entries). Ignore blob/data for download; keep listening after page finish

## 1.5.7 (versionCode 24)

- Honest errors, singleTask recents, mediacodec bitrate, trim 1/1 persist

## 1.5.6 (versionCode 23)

- Aspect spinner: full labels (Original, Fill 16:9, Fit 9:16, …) on the selected line and in the dropdown — wrap/2 lines, no middle ellipsize

## 1.5.5 (versionCode 22)

- In-app updater: unauthenticated `releases/latest` on demongroker/video-droid
- Download APK to cache with progress, then install via FileProvider (no browser)
- Fail closed: no GitHub token in the app

## 1.5.4 (versionCode 21)

- Start/End seconds row hidden unless Auto-trim is On (On/Off text stays on the switch)

## 1.5.3 (versionCode 20)

- Original + trim off: save as-is (no FFmpeg re-encode)
- Original + trim on: stream copy (`-c copy`); mediacodec then mpeg4 if copy fails
- Fill/Fit encode keeps source fps (no `-r 30`, no `fps=` in vf)
- Export height spinner removed; filenames use download quality
- Recommended: best / original / trim off

## 1.5.2 (versionCode 19)

- Convert tries **h264_mediacodec** first (fast), then **mpeg4** `-q:v 6` once. No libx264
- Aspect labels: Original / Fill (crop) / Fit (pad). Twitter stays Fit 9:16
- Recommended notice clears when quality / height / aspect / export change

## 1.4.9 (versionCode 14)

- Chaquopy **16.1.0** with on-device Python **3.12** so yt-dlp **2026.7.4** imports (15.0.1 runtime was <3.10)
- Same 1.4.8 UI (Recommended, More, Open page, filenames, Export, trim seconds)

## 1.4.8 (versionCode 13) — shipping

- Recommended: download best, export 720, original. My last restores your custom set
- More (collapsed): height, aspect, trim, Login X
- Fail → Open page: in-app WebView sniffs mp4/m3u8/twimg/googlevideo
- Filename includes quality: `videodroid_1080_<time>.mp4` or `_720_` / `_best_`

## 1.4.7 (versionCode 12)

- Auto-trim shows **On** / **Off** next to the switch
- Start / End fields labeled **seconds** (5 = 5 seconds off the start, 3 = 3 seconds off the end)
- Same convert math as 1.4.6

## 1.4.6 (versionCode 11)

- **Export** switch
  - Off: download only, save the file as-is, no FFmpeg. Button says Download
  - On: convert. Button says Download & Convert
- Export off hides height / aspect / trim
- Export on: height default **1080** (1080, 720, 480, 1440)
- mpeg4 quality `-q:v 6` (was 23)
- Remembers last quality, export on/off, height, aspect

## 1.4.5 (versionCode 10)

- Bundled yt-dlp **2026.07.04** (was 2024.10.22)
- YouTube format list works again (android client in the old pin was empty)
- If format still missing: retry `best*` / merge

## 1.4.4 (versionCode 9) — last version that was on :8899 before this ship

- YouTube selector: merge video+audio (`bv*+ba/b`)
- YouTube clients: android + ios + web
- Still used stale yt-dlp → YouTube `Requested format is not available` on the phone

## 1.4.3 (versionCode 8)

- Convert no longer uses `libx264` (not in the kit)
- Encode with **mpeg4** so crop/scale/trim can finish

## 1.4.2 (versionCode 7)

- Cancel works during extract (not only after bytes start)
- X/Twitter: if yt-dlp sees no video, try public fxtwitter/vxtwitter for the mp4
- Errors: `No video in this tweet` vs `Need X login`

## 1.4.1 (versionCode 6)

- Status + notification: `Downloading N%`
- Notification stays until that job ends
- Wait for notification permission before starting the service

## 1.4 (versionCode 5)

- Foreground download so lock screen does not kill the job
- Cancel on the same Download button
- Remember last download quality
- Tap Done to open the file
- Same one screen: paste → quality → Download

## 1.3 and earlier

- 1.3: Login X, cookies stay on the phone
- 1.2: Chaquopy init, private updater, arm64-only
- 1.1: export-height parse + pad centering
- 1.0: on-device yt-dlp + FFmpeg paste/share downloader

## Later

- Changelog screen inside the app

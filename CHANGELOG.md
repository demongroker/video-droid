# VideoDroid changelog

Private phone app. Updates: public GitHub Releases (no token in the APK).

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

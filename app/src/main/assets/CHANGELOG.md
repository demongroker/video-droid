# VideoDroid changelog

Private phone app. Updates: public GitHub Releases (no token in the APK).

## 2.5.2 (versionCode 99)

### Field-log fixes after 2.5.1

- **The tiny-clip guard actually fires now.** 2.5.1 only blocked pages whose clips had a *known*
  small size; links that were never measured (no HEAD, no duration) slipped through, so the
  fallback still downloaded a `video.twimg.com` ad off a page with an embedded player. An
  unmeasured foreign-CDN link now counts as clip-shaped: you get "Only small clips on this page —
  try Scan page" instead.
- **The last "Waiting for connection…" string is gone.** The connectivity callback still published
  the old wording on a Wi-Fi handover; it now says "Connection lost — reconnecting…" and only when
  ConnectivityManager agrees the phone is offline.

## 2.5.1 (versionCode 98)

### Field-log fixes

- **Empty downloads fail properly.** A job that wrote 0 bytes, or a 20–40 KB HTML error page
  instead of a video, now fails with "Download was empty — try Scan page" instead of reporting
  Saved. Real-but-short transfers keep the specific "incomplete" wording.
- **No more ads instead of the video.** When every candidate on a page is a small clip served
  from a foreign CDN, the fallback refuses to auto-download and sends you to the Scan page
  picker, because the real video is behind an embedded player the static sniffer cannot see.
- **Honest connection wording.** A slow or stalled read while the phone is online now reads
  "Slow connection — retrying"; "Connection lost — reconnecting…" is reserved for a real
  dropout. The old text claimed the network was gone while bytes were still arriving.
- **Real host in job headers.** Shared text and bare domains resolve to a host instead of
  "(unknown)"; genuinely linkless jobs say "(no link)".
- **Log privacy.** New Settings → "Include page titles in copied log" (default off): titles are
  replaced with [title] unless you opt in.

## 2.5.0 (versionCode 97)

### Scan page for videos

When a site is not supported, the app used to guess one media URL and hope. Now it can show you
what the page actually offers and let you pick.

- **Download tab → "Scan page for videos"** (and the same flow from a shared link): the page is
  fetched once, then parsed for `<video>`/`<source>`, `og:video`/`twitter:player:stream`, JSON-LD
  `VideoObject` (including its `duration` and `contentSize`), bare `.mp4/.webm/.m3u8/.mpd` URLs in
  markup and inline scripts, and same-origin iframes one level deep.
- **Ranked, not guessed**: `CandidatePicker` orders the results — the page's own host beats a
  foreign CDN, then the largest size, then the longest duration. Clips under 3 MB or 30 s only win
  when they are the only candidate, which is what stopped a 5 MB ad being downloaded instead of
  the video.
- **A picker that shows its work**: bottom sheet with one row per candidate (host, type, size,
  duration, "this site" marker), the best preselected, several selectable. Nothing is downloaded
  until you confirm, and every pick still passes the saved-link duplicate check before transfer.
- **DRM is respected, never bypassed**: streams with encryption markers (Widevine/PlayReady/
  FairPlay/ClearKey/`#EXT-X-KEY`/*encrypted*) are listed greyed out as "Protected — this video
  can't be saved".
- **Sizes come from HEAD/range requests only**, on a background thread, with an 8 s timeout. No
  body is fetched during the scan and no cookie is attached.
- **Generic by design**: no site names anywhere in the code, no per-site extractor, no login or
  paywall circumvention.
- Tests: `PageScannerTest` (10 cases) over hand-written fixtures in
  `src/test/resources/fixtures` — plain `<video>`, `og:video`, JSON-LD with duration/size, HLS in
  an inline script, same-origin iframe, ad-clip-plus-main, DRM page, no-video page, ISO-8601
  durations, and relative-URL resolution.



### "GitHub: NetworkOnMainThreadException" — the updater never worked
The screen recording showed a toast with that exception after closing the Changelog, so the
in-app update check failed every single time.

- **The cause was one line**: `UpdateChecker.check()` called a blocking HTTP request on the
  caller's thread — the UI thread — from both the launch check and About → Check for updates.
  Android forbids that and throws `NetworkOnMainThreadException` before the request is made.
- **Fixed**: the whole fetch now runs on a dedicated background thread and only the result is
  posted to the UI. Nothing else about the flow changed: same GitHub lookup, same fail-closed
  signature check, same install prompt.
- **Errors are readable**: the updater's failures go through `ErrorText`, so the owner sees
  "Couldn't check for updates — try again" instead of an exception class name. The raw exception
  goes to the local non-fatal crash log, which is where diagnostics belong.
- **StrictMode in debug builds only** (`detectNetwork`, `detectDiskReads`, `penaltyLog`): the
  next main-thread network or disk touch is logged the first time it happens instead of reaching
  the phone. Release builds are unaffected.
- **The in-app Changelog is now generated from `CHANGELOG.md` at every build** (Gradle
  `syncChangelog` before `preBuild`), so it can never again stop at an old version — the viewer
  was showing 2.3.6 while the app was on 2.4.x. `ChangelogAssetTest` fails the build if the
  bundled copy drifts behind the app version.
- Tests: `UpdateFlowTest` (newer/same/older comparisons, tag normalisation, plain-English
  failure text, no dialog when there is no release) and `ChangelogAssetTest`.



### Audio only, subtitles, and logs that can be read

- **Audio only.** A new setting (Settings → Audio only) makes the next jobs fetch the best audio
  stream — m4a first, then opus, then whatever the site offers — with no video pipeline at all.
  Files are named `<title>_<date>_audio.<ext>` and saved as `audio/*`, so the Library badge and
  "Play in Vela" both do the right thing. MP3 conversion is deliberately not included: it would
  add nothing without a new encoder path, and ffmpeg-kit already ships.
- **Subtitles.** Settings → Download subtitles cycles **off · auto · all**, and Settings →
  Subtitle language picks the preferred tag (device language by default, then English). yt-dlp
  embeds the tracks when the container allows it (mp4/mkv) and leaves a `.srt` beside the file
  when it does not. Nothing is uploaded.
- **Per-job log sections.** Parallel jobs no longer interleave in the copied log: each job gets
  its own block — short id, host only (never a full URL), start, end, result — followed by that
  job's lines. Everything still passes through the redactor, so no query string or token can end
  up in a log that gets pasted into a chat.
- Tests: `SubtitlePrefsTest` (mode cycle, language normalisation, device fallback) and
  `JobLogTest` (host-only extraction, section layout, redaction, trimming).

### Earlier in the 2.4.0 line (shipped in 2.3.7 and before)
AMOLED design system with an 8dp grid and one type scale · adaptive icon with a monochrome
layer · About screen · Library 2-column grid, list/grid toggle, shimmer placeholder and middle
ellipsis · Play in Vela · Quick Settings "Clipboard link" tile · Material You toggle · FLAG_SECURE
on dialogs and sheets · correct plurals · pre-download duplicate gate · human-readable errors with
Retry/Remove · queue rows that say "Waiting for connection" / "Queued" · no more
"duration: not reached" · ghost notification fixed · page fallback picks the real video.



### Phase 2 "Beautiful" — first slice
Design foundation and the screens that benefit most. The downloader-side upgrades (audio-only,
subtitle embedding) are NOT in this build; see the note at the end.

- **One design system.** `values/dimens.xml` holds the 8dp spacing grid, 16dp card corners,
  control heights and a single type scale (`Text.VideoDroid.*`). Palette re-tuned to AMOLED:
  pure black `#000000`, surfaces `#121212` / `#1C1C1E`, accent `#8B5CF6`.
- **Material You toggle** — Settings → Appearance. Off by default (VideoDroid's own black +
  violet); when on, `DynamicColors` takes the wallpaper palette. Applying it needs an app
  restart, which the toggle says.
- **New adaptive icon**: purple download-arrow mark on a near-black tile, with a **monochrome
  layer** for Android 13+ themed icons (`mipmap-anydpi-v33`).
- **About screen** (new): icon, version, check for updates, changelog, GitHub link, licences,
  and **Crash logs moved here** from Settings. The version line is also the seven-tap
  test-crash trigger in debug builds.
- **Library**: 2-column grid by default with a List/Grid toggle (remembered), purple **shimmer
  placeholder** while a thumbnail decodes instead of a flat box, and middle-ellipsis titles so
  the end of a filename stays readable.
- **Play in Vela**: tapping a video offers Vela when it is installed, otherwise the system
  player, otherwise the share sheet.
- **Quick Settings tile** "Clipboard link": one tap queues the link on the clipboard (validated
  with the same `ClipboardLink` rules as the in-app banner).
- **FLAG_SECURE covers dialogs and sheets too** — previously the flag was on the activity only,
  so a dialog could appear in a screen recording. A single `SecureScreen.secure()` now covers
  dialogs, bottom sheets and popups.
- **Haptics** on queueing, destructive confirms and the About tap sequence.
- **Correct plurals**: one `Texts` helper ("1 video" / "2 videos", "1 selected"), unit tested.
- Tests: 103 in total (`TextsTest` plus the existing suite).

Still to come (not in 2.4.0): audio-only downloads with cover art, subtitle download/embedding
with a language choice, drag-to-reorder in the queue, and the quality picker moving from chips
into a bottom sheet. Those touch the download pipeline and need device verification before they
ship.

## 2.4.0 (versionCode 95)

### Audio only, subtitles, and logs that can be read

- **Audio only** (Settings): the next jobs fetch the best audio stream — m4a first, then opus,
  then whatever the site offers — with no video pipeline. Files are named
  `<title>_<date>_audio.<ext>` and saved as `audio/*`, so the Library badge and Play in Vela do
  the right thing. MP3 conversion deliberately not included.
- **Subtitles** (Settings): off · auto · all, plus a preferred language (device language first,
  English fallback). Embedded when the container allows it, otherwise a `.srt` is saved beside
  the file. Never uploaded.
- **Per-job log sections**: parallel jobs no longer interleave in the copied log — one block per
  job (short id, host only, start, end, result, then that job's lines), all redacted.
- Tests: `SubtitlePrefsTest`, `JobLogTest`.

## 2.3.7 (versionCode 94)

Field log from the phone (Xiaomi 2510DPC44G, Android 16) — the last fix pass before 2.4.0.

### Ghost notification
After a duplicate skip and a cancel the shade kept showing
"Downloading 1 · Waiting for connection… (resumes automatically)" with nothing running.

- The notification is now derived from the queue through one object (`NotificationState`): no
  in-flight and no waiting rows means no notification at all, and the card is removed.
- `publishGlobal()` refuses to post when the queue holds nothing runnable, so a late progress
  update can no longer resurrect a card the service already removed.
- "Waiting for connection" is only claimed when ConnectivityManager itself reports no internet
  — a Wi-Fi handover fires `onLost` while the phone is still online.
- Launch reconciliation: if the process died with active work but an empty queue, the residual
  WorkManager job is cancelled and the notification cleared on the next start.
- Tests: `NotificationStateTest` (skip, cancel, Wi-Fi drop, queue counting, reconcile matrix).

### Page fallback picked the wrong video
The static-page fallback downloaded a 5 MB CDN clip (an ad) instead of the video. Candidates are
now ranked by `CandidatePicker`: clips under 3 MB or 30 s are ignored unless they are the only
candidate, the page's own host wins over a bigger foreign file, and the largest size then the
longest duration decide the rest. `CandidatePickerTest` covers each rule. (The full "Scan page for
videos" picker with thumbnails stays in 2.4.0.)

### Also in this build (from the 2.4.0 line, shipped early)
- Duplicate check **before** the download: canonical host+path keys, "Already in library" with
  "Download again anyway", SHA-256 still checked afterwards.
- Human-readable failures ("This post has no video", "This video is gone", …) with Retry/Remove
  in the Queue; raw errors stay in the log.
- Queue rows say "Waiting for connection" when offline and "Queued" instead of a bare 0%.
- No more "duration: not reached" line.
- Design refresh: AMOLED palette, 8dp grid + type scale (`dimens.xml`), 16dp corners, adaptive
  icon with a monochrome layer.
- About screen (version, updates, changelog, GitHub, licences, crash logs).
- Library: 2-column grid, List/Grid toggle, purple shimmer placeholder, middle ellipsis.
- Play in Vela, Quick Settings "Clipboard link" tile, Material You toggle.
- FLAG_SECURE now also covers dialogs and bottom sheets.
- Correct plurals ("1 video", "2 videos").

## 2.3.6 (versionCode 92)

### Local crash log — the phone tells you what broke
Downloads fail on a phone nobody can attach a debugger to, so the app now keeps its own
record of what went wrong. Nothing is uploaded, there is no analytics SDK, and nothing leaves
the device unless Share is tapped.

- A global uncaught-exception handler writes `filesDir/crashes/crash_YYYY-MM-DD_HH-mm-ss.txt`
  and then hands off to the platform handler, so the app still closes normally.
- Each record carries the timestamp, app version + versionCode, Android version, device model,
  thread name, full stack trace, and the last 200 lines of the in-memory app log.
- Caught worker errors (download / FFmpeg / yt-dlp failures) are stored beside them as
  `nonfatal_…` entries, so an "it just fails" job leaves a trace too.
- Newest 20 files are kept; older ones are deleted automatically — non-fatal records first, so
  a real crash is the last thing thrown away.
- **Privacy:** every line passes through a redactor that strips URL query strings and fragments
  and blanks out cookie or token material before anything is written.
- **Settings → Crash logs** lists the records newest-first, opens one to read, and offers Copy,
  Share (Android share sheet via FileProvider) and Clear all behind a confirmation.
- After a crash, the next launch shows a one-time banner: "VideoDroid crashed last time — view
  log?" with View / Dismiss.
- Debug builds: seven taps on the version line in Settings forces a test crash, so the writer
  and the viewer can be checked on demand. The body of that helper is behind `BuildConfig.DEBUG`
  and is compiled out of release builds.

## 2.3.5 (versionCode 91)

### The hardening pass before this went public
Six real gaps, each closed with a test or a guard.

- **Release build.** R8 minification plus resource shrinking, `debuggable=false`, signed with the
  existing release key so 2.3.5 installs straight over 2.2.6. The debug APK stays for testing.
- **Engine integrity.** The yt-dlp wheel is now checked against PyPI's published SHA-256 before it
  is unpacked. A truncated or tampered download is discarded and the working engine is kept,
  instead of every download breaking with an import error.
- **Move safety.** Library "Move" compares the SHA-256 of the source and the copy after writing.
  The original is removed only when they match — a copy that differs by one byte keeps the
  original and deletes the copy.
- **Delete on Android 7–10.** The confirmation dialog remains the only way in, and Android 10's
  `RecoverableSecurityException` consent intent is now launched instead of being swallowed as a
  silent failure.
- **Name collisions.** Same title + date + quality no longer overwrites: `_2`, `_3` … up to `_99`,
  then a timestamp.
- **Shared text.** A share is validated before it reaches the queue; a share with no usable link
  says "No video link in that share" instead of doing nothing.
- **FLAG_SECURE everywhere.** Applied through one `SecureScreen` helper to Download, Queue,
  Library and Settings, so content cannot appear in a screenshot or the Recents thumbnail.
- Also: one-time background hash backfill for the existing library (capped per run, resumable),
  Library thumbnails decoded off the UI thread, and one retry for a queue thumbnail that failed.

## 2.3.4 (versionCode 90)

### Stage 5 — files and engine
- Names are `<title>_<date>_<quality>.<ext>`; X posts take the date from the status snowflake and
  keep the short status id as the duplicate anchor.
- Duplicate detection gained **content identity**: the finished file is hashed with SHA-256 and
  compared against `saved_hashes.json`, so the same bytes under another name are caught even when
  the link is different.
- yt-dlp can be refreshed without a new APK: the latest pure-Python wheel is unpacked into the
  app's files directory and preferred over the bundled copy, on unmetered networks, once a week.

## 2.3.3 (versionCode 89)

### Stage 4 — Library
- Sort by newest / largest / name, remembered between launches.
- Size and duration badges on every thumbnail.
- Long-press multi-select with a contextual bar: Share (one or many), Move (copy, verify, then
  remove the original), Delete (asks first, then the system media dialog on Android 11+).
- Find duplicates moved next to the storage bar.
- Privacy: FLAG_SECURE, an optional fingerprint/PIN lock for the Library, and a `.nomedia`
  "Hide from Gallery" toggle.

## 2.3.2 (versionCode 88)

### Stage 3 — Queue
- Empty state: "Paste a link to start" with a Go to Download button.
- Every row shows a real thumbnail — the page's own preview image (`og:image`, `twitter:image`,
  `<video poster>`) or a frame from a local file — with the media name as the headline and
  state · quality · live speed and ETA underneath.
- Progress bar whenever progress is known (paused and waiting included), an explicit Retry on
  failed rows, and per-row pause / resume / cancel.
- Foreground notification with progress; auto-resume after a dropped connection.

## 2.3.1 (versionCode 87)

### Stage 2 — faster input
- Share-to-download now matches any `text/*` share. YouTube, Instagram and most browsers were
  sending `text/html` and being ignored entirely.
- Opening the app with a video link on the clipboard shows a "Download this?" banner. It never
  auto-starts, stays dismissed for that link, skips links already queued, and ignores bare hosts,
  root paths and `content://` URIs.

## 2.3.0 (versionCode 86)

### Stage 1 — layout cleanup
- The Download tab is now just the link box with paste, one big Download button, the quality
  chips and one Options button.
- Social presets became a single sideways-scrolling chip row; the "More" list is gone.
- Re-encode, aspect, ratio and trim live only in the Options sheet, and aspect greys out with a
  hint until "Re-encode on device" is on.
- New Settings screen behind a gear: simultaneous downloads, battery, X login and clearing,
  local video, last link, recommended settings, copy log, check update, changelog — plus the
  later privacy toggles. "Exit VideoDroid" was removed.

## 2.2.6 (versionCode 85)

### "Already in library — skipped" was refusing brand-new videos
Reported as *"tried to download this but always crash"*. The device log showed the truth:
`Downloaded: 425 MB` → `Already in library — skipped`, three times, ~1.3 GB burnt and nothing
saved. The duplicate guard matched the six-digit status tag with a plain `LIKE '%<tag>%'`, so
**any** file whose name contained those digits anywhere counted as the same video — and a
Library full of `video_720p_Original_<timestamp>.mp4` names is full of digit runs.

- Exact display-name match first, then a **segment-anchored, escaped** match
  (`LIKE '%\_<tag>\_%' ESCAPE '\'`): `_861035_` must be its own underscore-delimited piece.
- The `Download/VideoDroid/duplicates` folder no longer counts as "present", so moving files
  aside with *Find duplicates* actually frees the name up.
- For an X link the tag is known from the URL, so the check runs **before** the download
  instead of after 425 MB.

Also in this build: **2.2.4** (nothing truncated can be reported as Saved — `.part`, <1 KB,
short versus advertised size, unreadable container) and **2.2.5** (per-job progress callback
and per-job diagnostics, so parallel downloads stop mixing their percentages and exceptions
in the log).

## 2.2.5 (versionCode 84)

### Diagnostics stop lying about which job is which
The "Copy log" output from a real device showed one job's block containing
`Downloading 22 %, 85 %, 23 %, 24 %` and two `Saved` lines, and an `NoVideoInTweet`
exception attached to a block that had downloaded 425 MB successfully. Three shared statics
were to blame, and all three are now per job:

- `dl.py` wrote progress to one module-global string and each worker polled it, so with the
  default three parallel downloads every job displayed whichever percentage was written last.
  Progress is now a **per-job callback** handed into Python and invoked on that job's own
  thread (same bridge the cancel check uses); the polling thread is gone. The progress
  throttle is per job too.
- `JobDiag` kept one ring buffer, `lastOpts`, `lastFfmpegRc` and `lastException` for every
  worker, so blocks mixed statuses and exceptions from different downloads. Diagnostics are
  now bound to the job id (`JobDiag.bind/unbind`), each job persists its own block, and the
  on-screen diagnostics keep showing the latest activity.

This is why the log is worth having: it caught a bug in itself.

## 2.2.4 (versionCode 83)

### A dropped connection can no longer masquerade as "Saved"
Cut Wi-Fi/data mid-download and the app could report **"Saved"** for a truncated file and put
it in the Library. The save path trusted whatever the downloader handed it and never looked at
size or readability.

Three gates now stand between a transfer and MediaStore:
- **In Python (`dl.py`)** the produced file is verified before it is reported as a success: a
  `.part` name, fewer than 1024 bytes, or less than 90 % of the advertised
  `filesize`/`filesize_approx` raises `IncompleteDownload` instead of returning a path. The
  direct-download fallback (used by the X path) also compares the bytes it read against
  `Content-Length` — urllib ends the read quietly when the peer closes early, which is exactly
  how a short file used to look complete.
- **In Kotlin (`DownloadIntegrity`)** the file is checked again before any save
  (`.part`, `< 1 KB`, short versus advertised size, and a container that FFprobe cannot read at
  all). The same gate guards the "convert failed, keep the original" path.
- **Reporting** says what happened — *"Download incomplete — nothing was saved (short: N of ~M
  bytes)"* — and that message is treated as network-shaped, so the row retries with backoff
  instead of being parked as permanently broken or silently indexed as media.

## 2.2.3 (versionCode 82)

### The tap artifact, actually fixed
2.2.1 put the pill back on the visible screen inside the tab listener — but
`NavigationBarView` applies the tapped item's check *after* that listener returns, so it
overwrote it. The result was the frame in the recording: **Download content on screen with the
Queue pill lit**, for as long as the transition lasted.

The pill is now also re-applied on the next frame, so the visible screen keeps its own
highlight until the next screen's `onResume` sets its id. A Robolectric test performs the same
call path a real tap uses (`selectedItemId` → `performItemAction` → listener), idles the
looper, and asserts both that the pill is back on the current screen **and** that navigation
still happened — it fails on the 2.2.1 behaviour.

## 2.2.2 (versionCode 81)

### The ghost "Working…" screen
With an empty queue the Download screen kept its last queue state: the status pill said
"working", the card said "Working..." and **Cancel current** stayed lit — while the Queue tab
correctly showed "Nothing queued". `refreshFromQueue()` simply returned the unchanged state
when `q == 0`.

Now an empty queue clears exactly the queue-derived state — busy flag, progress bar and lines
like `Working…`, `Queued (N)`, `Starting`, `Cancelling`, `Paused`, `Stopped`, `Downloading`.
Result and error messages (`Saved. Tap to open`, `Need X login`, `Already in library`) are
deliberately kept readable.

## 2.2.1 (versionCode 80)

### Bottom bar follows the screen, every time
This app is three activities, not a Navigation graph, so there is no `NavController` to bind
`selected` to — the fix is the activity-level equivalent:
- The pill is applied **immediately** and again after layout. It used to be deferred with
  `nav.post { }`, which is the one-frame lag you could see when tapping a tab.
- `isSaveEnabled = false` on the bar, so `BottomNavigationView` can no longer restore a stale
  destination from instance state and fight the screen — that is how "Library content on
  screen, Download pill highlighted" happened.
- Tapping the tab you are already on no longer jerks the pill.
- Library's bar was **inert** (it called only `sync()`, never installed the listener, so tab
  taps did nothing there); `sync()` and `wire()` are now the same idempotent installer.

### Find duplicates dialog: Material 3 and off the UI thread
- Uses `MaterialAlertDialogBuilder` — the theme is `Theme.Material3.DayNight`, so the dialog
  now matches the rest of the app instead of rendering an AppCompat/Material 2 card. The body
  is one wrapping block that scales with the system font size.
- "Move extras" now runs the MediaStore updates on a worker thread behind a progress dialog
  and reports the real result, instead of a loop of `contentResolver.update()` calls on the
  UI thread.
- The Library grid itself is also loaded off the main thread.

## 2.2.0 (versionCode 79)

### A cancel that actually ends
2.1.9 stopped the service as soon as nothing was *runnable*, but it still refused to stop
while a worker thread was alive. A worker could be parked forever in the shared encoder's
lock (`CONVERT_LOCK.lock()` — unbounded and cancel-blind), so after a Cancel the thread never
exited, the service never left the foreground, and the card sat on "Cancelling…" over an
empty queue.

- The converter wait is now **cancellable and bounded**: it polls the cancel flag every
  200 ms and aborts with "Cancelled" instead of blocking indefinitely.
- A cancelled FFmpeg session is waited on for at most 5 s; after that the worker stops
  waiting and reports failure rather than pinning the thread.
- Cancel now arms a **6-second shutdown watchdog**: if nothing is runnable it stops the
  service regardless of a wedged worker (worker threads are daemons, so nothing is lost), and
  the `shuttingDown` flag keeps the notification from being re-posted. Any new command
  supersedes the watchdog.

## 2.1.9 (versionCode 78)

### The stuck "Cancelling…" notification is gone
The foreground service decided whether to stop with `DownloadQueue.size()`, which counts
**failed and paused** rows. So after a Cancel (or a batch of failures) it never left the
foreground: the app correctly showed nothing queued while the shade kept a live card frozen
on "Cancelling…" with working Pause/Cancel buttons.

- The service now stops when **nothing can actually run** — no claimed item and no waiting
  row. Failed, paused and cancelled rows need a tap, so they no longer hold it open.
- Cancel parks the remaining rows as paused, so nothing silently restarts afterwards, and the
  service terminates immediately if no worker is still unwinding.
- Shutdown is now explicit: `stopForeground(STOP_FOREGROUND_REMOVE)` **and**
  `NotificationManager.cancel(NOTIF_ID)`, plus a `shuttingDown` flag so a per-item progress
  update that lands late cannot re-post the card — that race is what left the notification
  orphaned. The same cancel also runs in `onDestroy`.
- A sticky restart (`START_STICKY` with a null intent) no longer re-creates the foreground
  notification for a queue that only holds dead rows.
- The notification channel itself is kept — deleting it would drop the user's per-channel
  settings, which is a worse outcome than a stale id.

## 2.1.8 (versionCode 77)

### The log survives the crash
Job diagnostics lived only in memory, so the moment the app was killed — the exact moment
the log matters — everything was gone. Every finished job is now appended, **redacted**, to
`filesDir/diag.log` (rotated at 64 KB), and **Copy log** includes that saved history, the
device model and Android version, plus every queue row with its own failure reason,
attempts and progress. No cookie, token or tokenized URL is ever written.

### The header stops lying
The status pill next to the title was a hardcoded "ready", so the app claimed everything
was fine with a queue full of failed rows. It now reads "working", "working · N waiting",
"N in queue — tap Queue", or "ready" — from the real queue state.

## 2.1.7 (versionCode 76)

### X login stops poisoning every download
A `cookies.txt` whose `auth_token` has expired makes **every** X request fail with
"Could not authenticate you", while guest access still works for public posts. The
file was used whenever it was non-empty, so one stale login broke everything and
every row was reported as "Need X login".
- A job that fails in an authentication-shaped way now retries **once without the
  cookies**, and the dead file is renamed to `cookies.txt.stale` (never deleted) so
  the next job does not hit it again. You get "X login expired — cleared."
- Only messages that actually mention authentication may say "Need X login". A
  401/403, a protected or deleted post, or a rate limit now reads
  *"X refused this request — post may be private, deleted or rate limited"* and is
  treated as retryable instead of being parked as permanent.
- A blocked FxTwitter/vxTwitter fallback is no longer reported as a login problem.

### Find duplicates in the Library
The Library header has a **Find duplicates** action. It groups videos that are the
same media (a `_<timestamp>` collision suffix, or the same X status tag under two
different names) and offers to move the extra copies to
`Downloads/VideoDroid/duplicates`. Nothing is deleted, and the grid hides that
folder. The storage line now says "(device storage)" so it is not mistaken for
library usage.

## 2.1.6 (versionCode 75)

### No more duplicate videos
Owners reported the same X video twice in the Library. Three separate causes, all fixed:

- **The same post counted as a different link.** An X share link carries a `?s=09`
  tail, and the same post arrives as `x.com/…/status/ID`, `twitter.com/…/status/ID`
  or `x.com/i/web/status/ID`. Compared as raw strings none of those matched, so the
  queue accepted the video twice. Links are now compared by canonical form (host
  normalised, tracking parameters dropped, trailing slash ignored) and, for X, by
  status id — all forms are the same row.
- **A second paste while the first was already saved** wrote a second file with a
  `_<timestamp>` suffix. The save stage now looks for the video first — by the
  tweet's status tag, else by exact file name — and reports "Already in library —
  skipped" instead of creating a copy. Nothing is ever overwritten or deleted.
- **Downloads with no usable title** (a direct MP4 resolved by the page fallback has
  none) were all called `video_720p_Original.mp4`, so each one after the first was
  written as `video_720p_Original_<timestamp>.mp4`. The name now carries a short tag
  derived from the link, so one link always produces one name.

## 2.1.5 (versionCode 74)

### Stopping one download no longer breaks the others
Cancel/pause was a single app-wide flag, so cancelling (or pausing) one queue row
stopped **every** download running beside it — and each of those recorded
"Cancelled" as a permanent failure, which is why unrelated rows turned up as
failed. The stop request is now per item: it carries the row's id through to
yt-dlp, only that row's FFmpeg session is cancelled, and a user cancellation is
no longer counted as a failure at all (the row is released, not marked broken).

### Retry backoff actually works
`attempts` and `retryAfter` were never written to `download_queue.json`, so every
read reset them to zero: the 3-attempt cap could never be reached and a network
failure re-looped every 30 seconds indefinitely. Both fields are now persisted.

### A killed process picks its queue back up
`QueueResumeWorker` was only armed by a reboot or an app update. The application
now schedules it on start, so a queue left behind by a killed process resumes
without waiting for the next reboot.

## 2.1.4 (versionCode 73)

### Direct-video sites no longer fail as "HEVC-only"
With **Export on** (the default) the download asked for
`bv*[vcodec^=avc1][height<=1080]`. That filter needs codec metadata, and a
direct MP4 link — or anything resolved by the page fallback (LiteSpeed
`player-x.php?q=` sites, plain `<video src>` pages, some HLS) — has none, so
yt-dlp answered "Requested format is not available" and the app reported
*"This video is HEVC-only"* about a perfectly ordinary H.264 file.

Now that answer is treated as what it is — a metadata mismatch, not a codec
verdict. The download retries without the codec gate (`best*/bv*+ba/b`, then no
format filter) and only a source that *actually* reports `hevc`/`hev1`/`h265`
is refused on the export path. Export-off behaviour is unchanged.

## 2.1.3 (versionCode 72)

### "Already in queue" warning actually stays visible
Tapping Add on a link that is already queued set the warning, but a running
download's live status stream overwrote it within a frame, so you saw nothing.
The warning now holds for ~3 seconds against the status stream before the live
progress text returns.

## 2.1.2 (versionCode 71)

### Exit VideoDroid
A new **Exit VideoDroid** button sits in the "More" section. Tapping it really
leaves — it stops the download service, cancels the resume worker, closes every
activity, and kills the process, so the app is gone rather than lingering in the
background. If a download is live it warns first: exiting stops the transfer, and
anything still queued stays queued for next time.

## 2.1.1 (versionCode 70)

### Duplicate links say so out loud
Tapping Add on a link that is already queued (live, paused, failed, or waiting out a
retry) used to do nothing — the queue's `enqueue()` returns -1 but that runs inside a
fire-and-forget service intent, so the owner tapped Add and saw no change. The check now
runs in the view model *before* the intent, and the screen reads "Already in queue — that
link is waiting. See the Queue tab."

### Speed and ETA on each row
While an item is actually transferring, its queue row now shows the live yt-dlp rate
("2.4MiB/s · 3m 12s left") instead of only the quality tag, and the notification detail
carries the same numbers.

### Pause / Resume from the notification
The foreground notification now carries a **Pause**/**Resume** action beside Cancel, so a
stuck transfer can be stopped from the shade without opening the app. The label reflects
what is actually paused right now.

## 2.1.0 (versionCode 69)

### Per-item pause / resume
2.0.3 could only pause the whole queue. Each row now has its own **Pause** button that
becomes **Resume** while paused, so one slow transfer can be stopped without touching the
rest. A paused row reads `Paused · 40% done` instead of showing a stale percentage, and
failed rows hide the button because there is nothing to pause.

### Bounded retry with backoff
A dropped radio used to park the item in the failed list until the owner came back and
tapped it. Failures are now classified:
- **network-shaped** (`no network`, `timeout`, `connection reset`, `unreachable`, …) are
  retried at **30 s → 2 min → 10 min**, up to 3 attempts, then marked failed for real.
- **permanent** (`profile link`, `no video`, `unsupported`, `404`, `need x login`,
  `private`, …) fail immediately — retrying a bad URL only burns battery.
The pump is woken at the earliest retry time, so a backed-off item is not stranded
"Waiting" until something else happens to arrive.

### Bulk actions
The header's Clear button is now a menu: **Retry failed (N)**, **Clear failed (N)**,
**Clear all**. The first two only appear when something has actually failed, so ten dead
links are one tap to clear and one tap to retry once X login is sorted.

## 2.0.4 (versionCode 68)

### Connectivity watchdog
- "Waiting for connection… (resumes automatically)" could hang for hours. The
  `NetworkCallback` alone was not enough: `onLost` latched a flag and if
  `onAvailable` never arrived (radio flap while the screen was off, doze, or the
  callback dying with the process) nothing ever re-checked, so the queue sat
  behind a promise the code did not keep.
- While the network is marked lost, the radio is now re-probed every 60 s. On
  success the flag clears, "Connection restored — resuming…" is published and the
  pump is kicked.
- After 5 minutes with no active network it stops pretending: publishes
  "No connection for 5m — paused. Tap Resume all." and goes quiet.

### Honest X/Twitter errors
`mapError()` now takes the URL, so "No video in this tweet" is split into what it
actually means:
- pasted URL has **no** `/status/` → *"That's a profile link, not a post — open
  the post and share its link"* (the commonest real mistake)
- has `/status/` → *"No video in that post (or X login needed)"*
- auth failures → *"Need X login — Settings → Log in to X"*, pointing at the
  screen that already exists

## 2.0.3 (versionCode 67)

### Pause and resume
- **Pause all / Resume all** in the queue header. The button reads
  `Pause all`, then `Resume all (N)` showing how many items are paused.
- Pausing **keeps the partial file and progress** — resuming continues the
  transfer instead of restarting it. This needed a new
  `FormatWorker.requestPause()`: the existing `requestCancel()` deliberately
  deletes the work directory, which is right for cancel and wrong for pause.
- Paused rows are skipped by the pump and are not counted as waiting.
  Failed rows are left visible as failures rather than being paused.
- `paused` is persisted in `download_queue.json`, so pausing survives a restart.

### Build
- `build.sh` now uses `./gradlew` (wrapper, Gradle 8.11.1). It previously
  called `gradle-8.9`, which the Android plugin rejects:
  *"Minimum supported Gradle version is 8.11.1."*
- `build.sh` links the keystore from `~/.video-droid-signing` and reads the
  passwords from `CREDENTIALS.txt` into env vars, so nothing is hardcoded.
- `lintVitalRelease` is skipped by default; pass `--with-lint` to include it.

## 2.0.2 (versionCode 66)

### Background, resumable updates
- Updates download in a foreground service with a status-bar notification
  instead of a modal dialog, so leaving the app no longer kills the transfer.
- Resumable: `Range`/`If-Range` with the stored ETag, `206` append vs `200`
  restart, and expired GitHub asset URLs are re-resolved while keeping the
  byte offset. Unit-tested against a local Range-capable server.
- Downloaded APK is still signature-checked before the installer opens.

### Options bottom sheet
- The Options button now opens a real bottom sheet editing quality, export,
  aspect mode/ratio and trim. Values are written back into the same widgets
  and persisted through the existing path.

### Queue actions
- Queue moved to RecyclerView: swipe left cancels (with confirm), swipe right
  shares, and failed items stay listed with their reason — tap to retry.

## 2.0.1 (versionCode 65)

### Startup crash fixed

v2.0.0 died instantly on Xiaomi/HyperOS ("VideoDroid keeps stopping"). Cause: the
redesigned layout used different view classes than the screen code casts to, so
`findViewById` threw `ClassCastException` while the activity was still inflating —
before any frame could be drawn.

- `exportSwitch` / `trimSwitch`: `SwitchMaterial` -> `android.widget.Switch`
- `jobProgress`: `ProgressBar` -> `Material LinearProgressIndicator`
- `presetsExpanded`: `LinearLayout` -> `Material ChipGroup`

### Crash catcher
Uncaught exceptions are now saved to app storage. On the next launch the app
opens a plain **"VideoDroid hit an error"** screen with the full stack trace,
device/Android/version header, **Copy log** and **Dismiss and continue**. No adb
needed.

### Hardening
- Clipboard is only read once the window has focus, and a failed read can never
  throw (some OEM builds deny or throw instead of returning null).
- Startup view wiring cannot abort `onCreate`.

### CI
Robolectric launch smoke tests: `MainActivity` on a clean install, `MainActivity`
with 1.9.5-style saved prefs and a persisted queue, and `LibraryActivity`. These
reproduced the crash before the fix and must stay green.

## 2.0.0 (versionCode 64)

### Redesign

- **AMOLED black** UI with **violet** primary and **emerald** accent. Fixed brand
  palette instead of wallpaper dynamic colour, so it looks the same on every
  phone. Pill and card styles throughout.
- **Download screen rebuilt**: paste bar with a clipboard button (a copied link
  is offered automatically when you come back to the screen), full-width
  Download button, **quality pills** (4K / 1080 / 720 / 480) instead of a
  dropdown, and a **progress card** with a thumbnail slot, status, speed/ETA
  line and a violet bar.
- **Library is now a thumbnail grid** with real MediaStore thumbnails, a
  **storage bar** (free / total / used %) and an empty state. Tap to play,
  long-press to share.
- Advanced controls (convert for social, aspect, trim, simultaneous downloads,
  battery, local file, open page, X login, diagnostics) live under **More**,
  reachable from the **Options** button.
- Bottom-bar icons and labels unchanged from 1.9.1.

### Not in this release
- The Options control reveals the existing More panel rather than a separate
  bottom sheet.
- Queue rows keep the 1.9.4 card layout; swipe actions are not in this build.

### Unchanged
Download, resume, parallel-download, page-fallback, signing and updater logic
are untouched by this release. All unit tests stay green.

## 1.9.5 (versionCode 63)

### Page fallback actually works on the phone

1.9.4's sniffer found nothing on the phone (`timed out with no candidates`) even
though a desktop fetch of the same page exposes everything. Diagnosis: the
media URL only exists in the **static HTML** (a lazyloaded iframe plus a base64
`?q=` player parameter) — a WebView on the device never got that far.

- **Static HTML path runs first.** The page is fetched with `HttpURLConnection`
  (desktop Chrome UA, Accept/Accept-Language/Referer, CookieManager jar,
  redirects followed) and parsed by a new pure-Kotlin `MediaUrlParser`: iframe
  `src` / `data-litespeed-src` / `data-src`, `<video>`/`<source>` src,
  `og:video`, `twitter:player`, JSON-LD `contentUrl`/`embedUrl`, `file:` blobs,
  and **base64 `?q=` payloads** (URL-safe alphabet and missing padding both
  handled). Player/iframe URLs are fetched **one level deep** and parsed too.
- **WebView is now only the fallback**, used when the static path finds nothing.
- **Diagnostics in Copy log**: HTTP status, final URL after redirects, page
  `<title>`, HTML length, Cloudflare/age/consent-wall detection, the iframe
  list, the first 300 chars of the decoded `q` payload, per-frame fetch results
  and which path won (`static` / `sniffer` / `iframe` / `generic` / `native`).
- **Unit test in CI**: `MediaUrlParserTest` runs against a saved fixture of the
  failing page shape and asserts the parser returns
  `https://rikywohu.b-cdn.net/gretgrg.mp4`. `testDebugUnitTest` is now part of
  the workflow.
- Found URLs still go through yt-dlp with **Referer** (player iframe or page),
  **Origin** and the desktop **User-Agent**, so partials, infinite retries and
  the queue still apply.

## 1.9.4 (versionCode 62)

### Parallel downloads

- **Up to N at once** (default **3**, setting **1–5** under *More →
  Simultaneous downloads*). The queue now hands out **claims**: a worker takes
  the next unclaimed item, and the claim is released when it finishes. Claims
  are in-memory, so a process death releases everything and a restart resumes.
- **Each item has a stable id** and its own partial directory, so parallel
  transfers never fight over the same `.part` file.
- **FFmpeg still runs one at a time.** The converter is behind a global lock;
  a worker that reaches it shows *"Waiting for converter…"* while another job
  encodes. Downloads keep running in parallel meanwhile.
- **Queue tab** rebuilt: every row shows live progress and its own **Cancel**
  (stop if running, remove if waiting). **Cancel all** stops everything, clears
  the queue and asks for confirmation first.
- **Download card** shows how many items are running and how many are queued.
- **No duplicates**: enqueuing a URL that is already queued is rejected with
  *"Already in queue"*.
- **Shared links go straight to the queue** using the current settings — no
  quality sheet blocking the flow.
- **Persistence**: unfinished items resume on app start and `BOOT_COMPLETED`,
  up to the configured parallelism.
- **Page fallback actually finds the video now** (fixes 1.9.3 finding nothing
  on some sites). Diagnosed on `porneec.com`: the player is a **lazyloaded
  iframe** (`src="about:blank"`, real URL in `data-litespeed-src`) and the
  `<video><source>` markup is **base64-encoded inside a `player-x.php?q=`**
  parameter. The sniffer now forces those frames to load, decodes `?q=` base64
  payloads, follows iframes (tries yt-dlp on each embed URL first), reads
  `data-src`/`data-video`, JSON-LD `contentUrl`/`embedUrl`, `og:video`,
  `twitter:player` and `file:`/`sources:` player blobs, dismisses age-gate and
  consent buttons, taps the player, and sniffs for **40s** showing
  *"Searching page for video… (Ns)"*. When nothing is found, the candidate list
  and iframe chain go into **Copy log** so the reason is visible.

- Notification shows a summary ("Downloading 2 · 3 queued") alongside the
  current stage.

## 1.9.3 (versionCode 61)

### Fallback for sites yt-dlp has no extractor for

- **Bundled yt-dlp updated** to the latest stable (**2026.8.19**), so new
  extractors may already cover some sites without any fallback.
- **Step 1 — generic extractor.** On `Unsupported URL`, retry once with
  `force_generic_extractor=True`, keeping the same quality, resume and retry
  settings.
- **Step 2 — page sniffer.** If generic also fails, the page is loaded in a
  hidden WebView (desktop UA, JS on, ~20s cap, first player auto-clicked) and
  media requests are captured (`.mp4`, `.m3u8`, `.mpd`, `.webm`, `.m4v`) along
  with `<video>` / `<source>` sources and `og:video`. Ad/tracker hosts are
  ignored and a streaming master playlist is preferred over a flat mp4.
- **The found URL goes back through yt-dlp** with `Referer` = page URL, the
  desktop User-Agent and the WebView cookies — so resumable partials, infinite
  retries and the queue still apply.
- **Status**: *"Site not supported — trying page fallback…"*, then
  *"Found video on page — downloading…"*. If nothing is found:
  *"No downloadable video found on this page"* — no crash, the queue moves on.
- **Debug**: the path that succeeded (native / generic / sniffer) and the chosen
  media host are recorded in the job diagnostics.
- **Filename** falls back to the sanitised page `<title>` when there is no
  extractor title.

## 1.9.2 (versionCode 60)

### Resumable downloads

- **Resume, don't restart.** Each source now gets its own partial directory
  keyed by a SHA-1 of the URL, and nothing wipes it at job start. Previously a
  single shared work dir was cleared on every run, throwing away the `.part`
  file and forcing a restart from zero. Partials are kept on network errors and
  removed only when the user cancels that item.
- **Never gives up on a drop.** `retries` and `fragment_retries` are infinite,
  `socket_timeout` went 8s → 30s, and a retry loop backs off 2/4/8/16/32/60s.
  While waiting, the status reads *"Waiting for connection… (resumes
  automatically)"* instead of failing.
- **Network watcher.** The service registers a default-network callback: losing
  the network shows the waiting message, and the radio coming back immediately
  publishes *"Connection restored — resuming…"* rather than waiting out a
  socket timeout.
- **Stays alive.** A partial wakelock and a high-performance Wi-Fi lock are held
  while a job runs (released on finish/cancel), on top of the existing
  `dataSync` foreground service and its progress notification. The service
  already returns `START_STICKY`, so a swipe from recents does not end the job.
- **Per-item state is persisted.** The queue entry now carries `progress` and
  `partialPath` alongside URL/quality, so a restart knows how far it got and
  where the partial lives. Unfinished items are re-enqueued on app start and on
  `BOOT_COMPLETED` (unchanged path, now with state to resume from).
- **Battery**: new *Battery* control in the More section shows whether Android
  may kill the app and opens the exemption dialog / per-app battery screen.
  The one-time prompt is unchanged.
- **Cancel Current** still stops and cleans up that item only.

## 1.9.1 (versionCode 59)

### Fixed
- **Bottom bar showed the wrong tab on launch.** On a cold start the Download
  screen was displayed but the Queue tab was highlighted, with an empty
  active-indicator pill. The bar now takes its selection from the screen that
  is actually on top (each activity passes its own tab id), re-asserting after
  the view-state restore and again in `onResume`, so the highlight survives
  cold start and back presses.
- **Bottom bar had no icons.** Added Download, Queue and Library icons, tinted
  by `colorPrimary` when selected and `colorOnSurfaceVariant` otherwise, with
  the labels kept underneath and inside the indicator pill.

## 1.9.0 (versionCode 58)

Phase 3 — Material 3 refresh. Still XML views, no Compose, no Hilt.

- **Edge-to-edge fixed.** targetSdk 36 draws behind the status bar and the
  gesture pill, so the title sat under the status bar. Every screen now pads by
  the system-bar + display-cutout insets.
- **Dynamic colour + dark palette.** Material You wallpaper palettes on
  Android 12+; indigo `#3949AB` fallback below that, with a real night palette.
- **Library screen.** Finished downloads, read back from
  `Download/VideoDroid`, newest first, tap to play.
- **Three-tab bottom navigation.** Download / Queue / Library; Queue lists the
  pending jobs and can clear them.
- **Share bottom sheet.** Sharing a link opens a quality
  (4K / 1080p / 720p / 480p) and convert-for-social picker before queuing.
- **Progress card.** Status + progress grouped in a Material 3 card that
  follows the dynamic palette.

## 1.8.0 (versionCode 57)

Phase 2 — architecture, queue durability and toolchain.

- **Screen logic moved into a ViewModel** (`ui/MainViewModel`): job state,
  queue counts, progress, last result and last failed URL now live in a
  `StateFlow`, and background work runs on `viewModelScope` instead of raw
  `Thread`s. No visual changes.
- **The download queue now survives restarts and updates.** `QueueResumeWorker`
  (WorkManager) re-launches the drain after a process death; `QueueResumeReceiver`
  re-arms it on boot and after an app update; `dl.py` keeps the `.part` file
  (`continuedl`) so an interrupted transfer resumes instead of restarting.
- **Toolchain**: compileSdk/targetSdk 36, AGP 8.9.2, Gradle 8.11.1,
  Kotlin 2.0.21 (Compose compiler Gradle plugin), AndroidX libraries current.
  minSdk stays 24.

## 1.7.0 (versionCode 56)

Phase 1 — hardening, CI and size.

- **Gradle wrapper + CI**: the repo now builds from the checked-in `gradlew` (8.9). New `.github/workflows/release.yml` runs on every PR/branch (debug + unsigned release, as a compile and size check) and produces a **signed** release on `v*` tags — one asset `VideoDroid-<version>.apk` plus `.sha256`.
- **Updater hardened**: removed the hard-coded private plain-HTTP fallback (`http://100.105.81.82:8899/videodroid/version`). Update checks now use GitHub releases only and the APK URL must be `https`. The signing-certificate check is unchanged.
- **Clear login narrowed**: clearing the X login now expires only x.com / twitter.com cookies instead of wiping the whole WebView cookie jar.
- **APK ~11.7% smaller**: `minifyEnabled` + `shrinkResources` enabled with keep rules for Chaquopy and ffmpeg-kit; `ffmpeg-kit-full` → `ffmpeg-kit-video` (only copy / aac / mpeg4 / h264_mediacodec are used). Release APK 58.1 MiB → 51.3 MiB.
- **Docs**: README updated to 1.6.28 behaviour (`best` removed, mpeg4 fallback documented); stale "instead of best (HEVC)" error text fixed; CHANGELOG 1.6.21 de-duplicated and reordered; pixel format corrected to `yuv420p` (not nv12).
- **Build portability**: Chaquopy no longer hard-codes a local `buildPython` path, and `gradle.properties` (AndroidX flags) is now tracked.

## 1.6.28 (versionCode 55)

- **mpeg4 fallback fix**: hwaccel (`-hwaccel mediacodec`) is now structural-only for `h264_mediacodec` encode attempts. The mpeg4 encoder path never receives hwaccel args — pure software decode + mpeg4 software encode works on any source the software decoder can read. Fixes rc 69 invalid-data failure on HEVC files in the mpeg4 fallback path.
- All 1.6.26/1.6.27 features preserved: H.264-required export download, quality dropdown without best, 4-attempt encode strategy (hwaccel → software decode → software+format → mpeg4 fallback).

## 1.6.27 (versionCode 54)

- **mpeg4 last-resort fallback**: encode now tries 4 attempts instead of 3. If hwaccel, software decode, and software+format all fail, the final attempt uses the **mpeg4** software encoder (`-c:v mpeg4 -q:v 4`). This decodes whatever FFmpeg's software decoders can handle and always produces a playable MP4 — even for sources that h264_mediacodec rejects (e.g. rc 69 invalid data, empty 261-byte output).
- Status shows **"Converting (fallback)..."** during the mpeg4 attempt so the user knows the output is MPEG-4 (plays on phone, but not X-uploadable). Success message reads `Saved (mpeg4 — plays on phone, not X-uploadable)` when the fallback was used.
- Export-ON downloads still require H.264 (AVC) sources — the mpeg4 fallback only applies when a downloaded source happens to be undecodable by h264_mediacodec.

## 1.6.26 (versionCode 53)

- **"best" removed**: quality dropdown is now 4K / 1080p / 720p / 480p only. All social presets (X, TikTok, Shorts, Reels, YouTube, Instagram, Facebook) and Recommended default to **1080p**.

## 1.6.25 (versionCode 52)

- **Export H.264-REQUIRED**: Export downloads now use AVC-only format (`bv*[vcodec^=avc1][height<=1080]+ba/...`) with **no any-codec fallback**. If the video is HEVC-only, the download fails fast with a clear message instead of grabbing 676 MB of undecodable HEVC. Export-OFF and 1080/720/480 presets unchanged.

## 1.6.22 (versionCode 49)

- Fix HEVC/H.265 input: encode uses **hardware decode** (`-hwaccel mediacodec -hwaccel_output_format yuv420p`) before the input file, so h264_mediacodec no longer fails with "Invalid data found when processing input" on H.265 downloads. The output pixel format is **yuv420p**, not nv12 — NV12 is not universally playable and breaks crop filters. Copy/remux path untouched, still pure stream copy.

## 1.6.21 (versionCode 48)

- Fix 1.6.20 regression: convert never started after download. Removed re-export feature (persistSource/lastSourceOrNull) which caused the encode path to stall. Local video picker and H.264-only encode unaffected.

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

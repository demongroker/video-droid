package com.xerxes.videodroid

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xerxes.videodroid.ui.DownloadSettings
import com.xerxes.videodroid.ui.Insets
import com.xerxes.videodroid.ui.JobSettings
import com.xerxes.videodroid.ui.MainCopy
import com.xerxes.videodroid.ui.MainScreen
import com.xerxes.videodroid.ui.MainUiState
import com.xerxes.videodroid.ui.MainViewModel
import com.xerxes.videodroid.ui.SocialPreset
import com.xerxes.videodroid.ui.UpdateSheet
import com.xerxes.videodroid.ui.UpdateSheetCopy
import com.xerxes.videodroid.ui.UpdateSheetModel
import com.xerxes.videodroid.ui.theme.VideoDroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Download (Main): paste a link, pick options, queue the job.
 *
 * The screen is Compose ([MainScreen]); this Activity owns every side effect — the duplicate
 * gate BEFORE any download intent, the share/scan/options sheets, the clipboard offer, the
 * update check and the updater progress sheet. Contracts unchanged: pause keeps the partial
 * file, cancel asks first, failure reasons stay in the state line, MP4/MKV only.
 */
class MainActivity : AppCompatActivity(), DownloadService.Listener, ShareSheetFragment.Listener,
    OptionsSheetFragment.Listener, ScanSheetFragment.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var pendingStart = false

    // Compose state: the ViewModel collector and the 1 s ticker write these; no re-setContent.
    private val url = mutableStateOf("")
    internal val settings = mutableStateOf(DownloadSettings())
    private val uiState = mutableStateOf(MainUiState())
    private val clipboardOffer = mutableStateOf<String?>(null)
    private val crashBannerVisible = mutableStateOf(false)
    private val updateChip = mutableStateOf<String?>(null)
    private val updateSheet = mutableStateOf<UpdateSheetModel?>(null)
    private var dismissedClipboardUrl: String? = null

    /** What the updater sheet is currently showing; the APK url is needed for Download/Retry. */
    private data class UpdateOffer(
        val version: String,
        val apkUrl: String,
        val action: UpdateAction,
        val message: String,
    )
    private var updateOffer: UpdateOffer? = null

    private val vm: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    /** Single source of truth for the status line; mirrored into the Compose state. */
    private var statusText: String
        get() = vm.state.value.statusText
        set(value) = vm.setStatus(value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // targetSdk 36 = enforced edge-to-edge; keep content out of the bars.
        SecureScreen.apply(window)
        Insets.applyTo(findViewById(R.id.mainWrapper))

        settings.value = loadSettings()
        crashBannerVisible.value = CrashLog.hasPending(this)

        // Settings hands the "Local video" action back here, where the picker already lives.
        if (intent?.getBooleanExtra(EXTRA_PICK_LOCAL, false) == true) {
            intent.removeExtra(EXTRA_PICK_LOCAL)
            findViewById<android.view.View>(R.id.mainWrapper).post { runCatching { pickLocalVideo() } }
        }

        NavHelper.wire(this, R.id.tab_download)

        findViewById<ComposeView>(R.id.mainCompose).setContent {
            VideoDroidTheme {
                Box(Modifier.fillMaxSize()) {
                    MainScreen(
                        ui = uiState.value,
                        url = url.value,
                        settings = settings.value,
                        clipboardOffer = clipboardOffer.value,
                        crashBanner = crashBannerVisible.value,
                        updateChip = updateChip.value,
                        onUrlChange = { text ->
                            url.value = text
                            if (text.trim().isEmpty()) OpenPageActivity.clearJobUrl(this@MainActivity)
                        },
                        onPaste = {
                            val link = clipboardUrl()
                            if (link != null) url.value = link
                            else toast("No link in clipboard")
                        },
                        onGo = { start() },
                        onCancel = { vm.cancel() },
                        onOpenResult = { openResult() },
                        onOpenFailedPage = { openFailedPage() },
                        onOptions = { openOptionsSheet() },
                        onScanPage = { scanPageForVideos() },
                        onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                        onQuality = { q ->
                            settings.value = settings.value.copy(quality = q)
                            persistCurrent(custom = true)
                            clearRecommendedNotice()
                        },
                        onPreset = { preset -> applySocialPreset(preset) },
                        onClipboardDownload = {
                            val u = clipboardOffer.value ?: return@MainScreen
                            url.value = u
                            clipboardOffer.value = null
                            persistCurrent(custom = false)
                            launchJob(u)
                        },
                        onClipboardDismiss = {
                            dismissedClipboardUrl = clipboardOffer.value
                            clipboardOffer.value = null
                        },
                        onCrashView = {
                            crashBannerVisible.value = false
                            CrashLog.clearPending(this@MainActivity)
                            runCatching { startActivity(Intent(this@MainActivity, CrashLogActivity::class.java)) }
                        },
                        onCrashDismiss = {
                            crashBannerVisible.value = false
                            CrashLog.clearPending(this@MainActivity)
                        },
                        onUpdateChip = { openUpdateChip() },
                    )
                    updateSheet.value?.let { model ->
                        UpdateSheet(
                            model = model,
                            onDismiss = { dismissUpdateSheet() },
                            onPrimary = { onUpdatePrimary() },
                            onCancel = { cancelUpdateDownload() },
                            onLater = { snoozeUpdateSheet() },
                            onToggleWifi = { toggleUpdateWifi() },
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { uiState.value = it }
            }
        }

        maybeAskIgnoreBattery()
        // If a job was still queued when the process died (or the app was
        // updated), WorkManager re-launches the service to finish it.
        QueueResumeWorker.schedule(this)
        handleIncomingShare(intent, enqueueIfBusy = true)

        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }

        // The update check must never race the first frame. Posting it to the
        // decor view means the home UI is drawn first, so a prompt (when one is
        // warranted) appears over a real screen instead of an empty window, and
        // no dialog can sit on top of a black screen while the check is in
        // flight. UpdateChecker additionally refuses to prompt while a download
        // is running, snoozed, offline or Wi-Fi-blocked.
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val pkg = packageManager.getPackageInfo(packageName, 0)
                    UpdateChecker.check(this@MainActivity, pkg.versionName ?: "0.0", userInitiated = false)
                } catch (_: Throwable) { }
            }
        }
    }

    fun jobIsRunning(): Boolean = vm.state.value.busy || DownloadQueue.size(this) > 0

    fun showUpdateStatus(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (!jobIsRunning()) statusText = msg
        }
    }

    private fun checkUpdateNow() {
        if (jobIsRunning()) {
            Toast.makeText(this, "Checking update…", Toast.LENGTH_SHORT).show()
        } else {
            statusText = "Checking update…"
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pkg = packageManager.getPackageInfo(packageName, 0)
                UpdateChecker.check(this@MainActivity, pkg.versionName ?: "0.0", userInitiated = true)
            } catch (t: Throwable) {
                runOnUiThread { showUpdateStatus("Update check failed: ${t.javaClass.simpleName}") }
            }
        }
    }

    override fun onResume() {
        // Reading the clipboard before the window has focus is denied on
        // Android 10+ and throws on some HyperOS builds. Defer until focused.
        window.decorView.post { if (hasWindowFocus()) runCatching { checkClipboardBanner() } }
        super.onResume()
        NavHelper.sync(this, R.id.tab_download)
        ticker = object : Runnable {
            override fun run() {
                refreshUpdateUi()
                handler.postDelayed(this, 1000L)
            }
        }.also { handler.post(it) }
    }

    override fun onPause() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        DownloadService.listener = this
        vm.refreshFromQueue()
    }

    override fun onStop() {
        if (DownloadService.listener === this) DownloadService.listener = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PICK_LOCAL, false)) {
            intent.removeExtra(EXTRA_PICK_LOCAL)
            runCatching { pickLocalVideo() }
            return
        }
        setIntent(intent)
        handleIncomingShare(intent, enqueueIfBusy = true)
    }

    private fun handleIncomingShare(intent: Intent?, enqueueIfBusy: Boolean) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Stage 2: accept every text flavour the share sheet can send, not just text/plain.
        val type = intent.type.orEmpty()
        if (!type.startsWith("text/")) return
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = firstUrl(raw).trim()
        // Stage 5b: a share can carry a caption, a hashtag or a bare host. Validate before
        // touching the queue, and say so instead of silently queueing nothing.
        if (!ClipboardLink.looksDownloadable(link)) {
            Toast.makeText(this, "No video link in that share", Toast.LENGTH_SHORT).show()
            return
        }
        url.value = link
        if (link.contains("twitter.com", ignoreCase = true) || link.contains("://x.com", ignoreCase = true) ||
            link.contains("://www.x.com", ignoreCase = true)) {
            settings.value = settings.value.copy(mode = "Fit", ratio = "Portrait 9:16")
        }
        // 1.9.4: a shared link goes straight to the queue using the current
        // settings — no sheet, no blocking, so parallel downloads can start
        // while the user is still in the other app.
        if (link.isNotBlank()) {
            persistCurrent(custom = false)
            launchJob(link)
        }
    }

    private fun maybeAskIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
            return
        }
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (_: Throwable) { }
    }

    private fun pickLocalVideo() {
        if (queueBusy()) {
            Toast.makeText(this, "Wait for the current job", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        try {
            startActivityForResult(i, REQ_LOCAL_VIDEO)
        } catch (_: Throwable) {
            statusText = "No file picker available"
        }
    }

    private fun start() {
        val link = url.value.trim()
        if (link.isEmpty()) { statusText = MainCopy.EMPTY_URL_NOTICE; return }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                pendingStart = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
                return
            }
        }

        // Field report: the same 425 MB video came down five times because the duplicate
        // check only ran after the transfer. Check the saved-link index first and offer a
        // deliberate override; the SHA-256 gate still runs afterwards.
        if (SavedLinkIndex.contains(this, link)) {
            Haptics.tick(this)
            showAlreadyInLibrary(link)
            return
        }

        launchJob(link)
    }

    /**
     * Generic page scan: fetch once, parse, probe sizes off the UI thread, let the owner choose.
     * Nothing downloads until a row is confirmed, and every pick still passes the saved-link
     * duplicate gate before it reaches the queue.
     */
    private fun scanPageForVideos() {
        val link = url.value.trim()
        if (!ClipboardLink.looksDownloadable(link)) {
            statusText = "Paste a page link first"
            return
        }
        statusText = "Scanning page…"
        Haptics.tick(this)
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { StaticPageFetcher.fetch(this@MainActivity, link) }.getOrNull()
            }
            if (page == null || page.html.isBlank()) {
                statusText = ErrorText.human(page?.error ?: "Couldn't load that page")
                return@launch
            }
            var found = withContext(Dispatchers.Default) {
                PageScanner.scan(page.html, page.finalUrl.ifBlank { link })
            }
            if (found.isEmpty()) {
                statusText = "No videos found on that page"
                return@launch
            }
            // Size probe: HEAD/range only, and only for candidates the page didn't describe.
            found = withContext(Dispatchers.IO) {
                found.map { c ->
                    if (c.sizeBytes > 0 || c.kind == PageScanner.Kind.IFRAME ||
                        c.kind == PageScanner.Kind.UNKNOWN
                    ) {
                        c
                    } else {
                        c.copy(sizeBytes = MediaProbe.sizeOf(c.url))
                    }
                }
            }
            statusText = "ready"
            ScanSheetFragment.newInstance(found)
                .show(supportFragmentManager, ScanSheetFragment.TAG)
        }
    }

    override fun onScanPicked(urls: List<String>) {
        var queued = 0
        var skipped = 0
        for (u in urls) {
            if (SavedLinkIndex.contains(this, u)) {
                skipped++
                continue
            }
            launchJob(u)
            queued++
        }
        statusText = when {
            queued > 0 && skipped > 0 -> "Queued $queued · skipped $skipped already saved"
            queued > 0 -> "Queued $queued"
            else -> "Already in library"
        }
    }

    /** "Already in library" before a byte is transferred, with an explicit way through. */
    private fun showAlreadyInLibrary(link: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Already in library")
            .setMessage("This link is already saved on this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download again anyway") { _, _ -> launchJob(link) }
            .show()
            .also { d -> SecureScreen.secure(d) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF && pendingStart) {
            pendingStart = false
            launchJob(url.value.trim())
        }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_PAGE && resultCode == Activity.RESULT_OK) {
            val media = data?.getStringExtra(OpenPageActivity.EXTRA_MEDIA).orEmpty()
            if (media.isNotEmpty()) launchJob(media)
        }
        if (requestCode == REQ_LOCAL_VIDEO && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            vm.importLocalVideo(uri) { path ->
                if (path != null) launchJob("", path)
            }
        }
    }

    private fun launchJob(link: String, localPath: String? = null) {
        if (link.isEmpty() && localPath.isNullOrBlank()) { statusText = "Paste a link or pick a video"; return }
        val s = settings.value
        val prefsNow = getSharedPreferences(PREFS, MODE_PRIVATE)
        persistCurrent(custom = false)
        vm.startJob(
            url = link,
            localPath = localPath,
            settings = JobSettings(
                s.quality, s.exportOn, s.aspectToken(),
                if (s.exportOn) s.trimOn else false,
                FormatOptions.clampTrimSeconds(s.trimStart),
                FormatOptions.clampTrimSeconds(s.trimEnd),
                audioOnly = prefsNow.getBoolean("audio_only", false),
            ),
        )
    }

    private fun openOptionsSheet() {
        val s = settings.value
        OptionsSheetFragment.newInstance(
            quality = s.quality,
            exportOn = s.exportOn,
            mode = s.mode,
            ratio = s.ratio,
            trimOn = s.trimOn,
            trimStart = s.trimStart,
            trimEnd = s.trimEnd,
        ).show(supportFragmentManager, OptionsSheetFragment.TAG)
    }

    /** Values from the sheet become the new settings snapshot, then persist. */
    override fun onOptionsApplied(
        quality: String,
        exportOn: Boolean,
        mode: String,
        ratio: String,
        trimOn: Boolean,
        trimStart: Int,
        trimEnd: Int,
    ) {
        settings.value = DownloadSettings(
            quality = quality,
            exportOn = exportOn,
            mode = mode,
            ratio = ratio,
            trimOn = trimOn,
            trimStart = FormatOptions.clampTrimSeconds(trimStart),
            trimEnd = FormatOptions.clampTrimSeconds(trimEnd),
        )
        persistCurrent(custom = true)
        clearRecommendedNotice()
    }

    private fun loadSettings(): DownloadSettings {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        return DownloadSettings(
            quality = p.getString(KEY_QUALITY, "1080p") ?: "1080p",
            exportOn = p.getBoolean(KEY_EXPORT_ON, true),
            mode = p.getString(KEY_ASPECT_MODE, "Original") ?: "Original",
            ratio = p.getString(KEY_ASPECT_RATIO, "Portrait 9:16") ?: "Portrait 9:16",
            // Trim on/off was never persisted (only the seconds); a fresh launch starts off.
            trimOn = false,
            trimStart = readTrimPref(p, KEY_TRIM_START),
            trimEnd = readTrimPref(p, KEY_TRIM_END),
        )
    }

    private fun persistCurrent(custom: Boolean) {
        val s = settings.value
        val ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, s.quality)
            .putBoolean(KEY_EXPORT_ON, s.exportOn)
            .putString(KEY_ASPECT_MODE, s.mode)
            .putString(KEY_ASPECT_RATIO, s.ratio)
            .putInt(KEY_TRIM_START, FormatOptions.clampTrimSeconds(s.trimStart))
            .putInt(KEY_TRIM_END, FormatOptions.clampTrimSeconds(s.trimEnd))
        if (custom && !s.isRecommended()) {
            ed.putString(KEY_CUSTOM_QUALITY, s.quality)
                .putBoolean(KEY_CUSTOM_EXPORT_ON, s.exportOn)
                .putString(KEY_CUSTOM_ASPECT_MODE, s.mode)
                .putString(KEY_CUSTOM_ASPECT_RATIO, s.ratio)
        }
        ed.apply()
    }

    private fun clearRecommendedNotice() {
        val t = vm.state.value.statusText
        if (t.startsWith("Recommended:")) statusText = ""
    }

    private fun applySocialPreset(preset: SocialPreset) {
        settings.value = settings.value.copy(
            quality = preset.quality,
            exportOn = true,
            mode = preset.mode,
            ratio = preset.ratio,
            trimOn = false,
        )
        persistCurrent(custom = true)
        clearRecommendedNotice()
        if (!jobIsRunning()) {
            statusText = MainCopy.presetNotice(preset)
        } else {
            toast("${preset.name} preset applied")
        }
    }

    private fun clipboardUrl(): String? = try {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        text.takeIf { it.startsWith("http") }
    } catch (t: Throwable) {
        // Android 10+ hides the clipboard without focus and some OEM builds
        // throw instead of returning null. Never fatal.
        null
    }

    /**
     * Show "Download this?" when the clipboard holds a link we have not offered yet.
     * Never auto-starts a download: the owner taps Download, so a stray clipboard URL
     * cannot burn data on ship Wi-Fi.
     */
    private fun checkClipboardBanner() {
        val link = clipboardUrl()
        if (link == null) { clipboardOffer.value = null; return }
        if (link == dismissedClipboardUrl) { clipboardOffer.value = null; return }
        if (link == url.value.trim()) { clipboardOffer.value = null; return }
        if (!ClipboardLink.looksDownloadable(link)) { clipboardOffer.value = null; return }
        val identity = MediaIdentity.canonicalUrl(link)
        val alreadyKnown = DownloadQueue.all(this).any {
            MediaIdentity.canonicalUrl(it.url) == identity
        }
        if (alreadyKnown) { clipboardOffer.value = null; return }
        clipboardOffer.value = link
    }

    private fun queueBusy(): Boolean = vm.state.value.busy || DownloadQueue.size(this) > 0

    private fun openResult() {
        val uri = vm.state.value.resultUri ?: return
        try {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
        } catch (_: Throwable) {
            statusText = "Could not open file"
        }
    }

    private fun openFailedPage() {
        val link = sequenceOf(
            vm.state.value.lastFailUrl.orEmpty(),
            OpenPageActivity.loadJobUrl(this),
            url.value.trim(),
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val i = Intent(this, OpenPageActivity::class.java)
        if (link.isNotEmpty()) {
            i.putExtra(OpenPageActivity.EXTRA_URL, link)
        }
        startActivityForResult(i, REQ_OPEN_PAGE)
    }

    override fun onStatus(msg: String) {
        vm.onServiceStatus(msg)
    }

    /** Result of the share bottom sheet: apply the pick, then queue. */
    override fun onShareConfirmed(link: String, quality: String, exportOn: Boolean) {
        settings.value = settings.value.copy(quality = quality, exportOn = exportOn)
        persistCurrent(custom = true)
        launchJob(link)
    }

    override fun onFinished(result: FormatResult) {
        if (result.ok && result.uri != null) {
            OpenPageActivity.clearJobUrl(this)
            // Persist last output for re-export reference
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_OUTPUT, result.uri.toString()).apply()
        }
        vm.onServiceFinished(result)
    }

    // ── Updater progress sheet ───────────────────────────────────────────────────

    /**
     * Entry point for UpdateChecker's single decision gate: the sheet renders the decision,
     * it never re-decides. Called on the UI thread by the checker.
     */
    fun showUpdateDecision(decision: UpdateDecision, version: String, apkUrl: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            updateOffer = UpdateOffer(version, apkUrl, decision.action, decision.message)
            updateSheet.value = buildSheetModel()
        }
    }

    private fun buildSheetModel(): UpdateSheetModel? {
        val offer = updateOffer ?: return null
        return UpdateSheetCopy.model(
            action = offer.action,
            decisionMessage = offer.message,
            version = offer.version,
            percent = UpdateState.jobPercent(this),
            doneBytes = UpdateState.jobBytesDone(this),
            totalBytes = UpdateState.jobBytesTotal(this),
            speedBps = UpdateState.jobSpeedBps(this),
            step = UpdateState.jobVerifyStep(this),
            jobError = UpdateState.jobError(this),
            wifiOnly = UpdateState.wifiOnly(this),
        )
    }

    /** 1 s cadence: the header chip plus the open sheet follow the persisted job state. */
    private fun refreshUpdateUi() {
        val active = UpdateState.isDownloadActive(this)
        val jobState = UpdateState.jobState(this)
        val jobVersion = UpdateState.jobVersion(this)
        updateChip.value = when {
            active -> "Update ${UpdateState.jobPercent(this)}%"
            jobState == UpdateJobState.VERIFIED && jobVersion != null -> "Update ready"
            jobState == UpdateJobState.FAILED && jobVersion != null -> "Update failed"
            else -> null
        }
        val offer = updateOffer ?: return
        if (updateSheet.value == null) return
        if (offer.action == UpdateAction.SHOW_DOWNLOADING) {
            if (!active && jobState == UpdateJobState.IDLE) {
                // Cancelled elsewhere (notification action): nothing left to show.
                updateSheet.value = null
                updateOffer = null
                return
            }
            val next = when (jobState) {
                UpdateJobState.VERIFIED -> UpdateAction.SHOW_READY
                UpdateJobState.FAILED -> UpdateAction.SHOW_FAILED
                else -> UpdateAction.SHOW_DOWNLOADING
            }
            if (next != offer.action) updateOffer = offer.copy(action = next, message = "")
            updateSheet.value = buildSheetModel()
        }
    }

    private fun openUpdateChip() {
        val version = UpdateState.jobVersion(this) ?: return
        when (UpdateState.jobState(this)) {
            UpdateJobState.DOWNLOADING -> {
                updateOffer = UpdateOffer(version, "", UpdateAction.SHOW_DOWNLOADING, "")
                updateSheet.value = buildSheetModel()
            }
            UpdateJobState.VERIFIED -> {
                updateOffer = UpdateOffer(version, "", UpdateAction.SHOW_READY, "")
                updateSheet.value = buildSheetModel()
            }
            // A retry needs a fresh pre-signed APK url, so re-run the check (user-initiated).
            UpdateJobState.FAILED -> checkUpdateNow()
            UpdateJobState.IDLE -> Unit
        }
    }

    private fun onUpdatePrimary() {
        val offer = updateOffer ?: return
        when (offer.action) {
            UpdateAction.SHOW_READY -> UpdateChecker.offerInstall(this)
            UpdateAction.PROMPT, UpdateAction.SHOW_FAILED, UpdateAction.WAIT_FOR_WIFI -> {
                // Same single-flight gate as the legacy dialog: a second tap says
                // "already downloading (x%)" instead of starting a second transfer.
                UpdateChecker.startDownload(this, offer.version, offer.apkUrl)
                if (UpdateState.isDownloadActive(this)) {
                    updateOffer = offer.copy(action = UpdateAction.SHOW_DOWNLOADING, message = "")
                    updateSheet.value = buildSheetModel()
                }
            }
            else -> Unit
        }
    }

    private fun cancelUpdateDownload() {
        UpdateService.cancel(this)
        updateSheet.value = null
        updateOffer = null
        // The downloader keeps the `.part` file + ETag: the next attempt resumes.
        toast("Update cancelled — it resumes where it left off next time")
    }

    private fun snoozeUpdateSheet() {
        val offer = updateOffer
        if (offer != null && offer.action != UpdateAction.SHOW_DOWNLOADING) {
            UpdateState.snooze(this)
            toast("Update reminder snoozed for 24h")
        }
        updateSheet.value = null
        updateOffer = null
    }

    private fun dismissUpdateSheet() {
        updateSheet.value = null
        updateOffer = null
    }

    private fun toggleUpdateWifi() {
        val now = !UpdateState.wifiOnly(this)
        UpdateState.setWifiOnly(this, now)
        toast(if (now) "Updates will download on Wi-Fi only" else "Updates may use mobile data")
        val offer = updateOffer ?: return
        val installed = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0"
        }.getOrDefault("0.0")
        // Re-run the one decision gate with the new Wi-Fi setting (user-initiated: snooze
        // must not swallow a deliberate tap).
        val decision = UpdatePrompt.decide(
            UpdatePromptInput(
                latestVersion = offer.version,
                installedVersion = installed,
                nowMillis = System.currentTimeMillis(),
                snoozeUntilMillis = UpdateState.snoozeUntil(this),
                jobVersion = UpdateState.jobVersion(this),
                jobState = UpdateState.jobState(this),
                jobPercent = UpdateState.jobPercent(this),
                jobError = UpdateState.jobError(this),
                online = UpdateState.isOnline(this),
                unmetered = UpdateState.isUnmetered(this),
                wifiOnly = UpdateState.wifiOnly(this),
                userInitiated = true,
            ),
        )
        if (decision.action == UpdateAction.NONE) {
            updateSheet.value = null
            updateOffer = null
            return
        }
        updateOffer = offer.copy(action = decision.action, message = decision.message)
        updateSheet.value = buildSheetModel()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun readTrimPref(prefs: android.content.SharedPreferences, key: String): Int {
        return try {
            FormatOptions.clampTrimSeconds(prefs.getInt(key, FormatOptions.DEFAULT_TRIM_S))
        } catch (_: Throwable) {
            FormatOptions.DEFAULT_TRIM_S
        }
    }

    private fun firstUrl(t: String): String {
        val m = Pattern.compile("https?://\\S+").matcher(t)
        return if (m.find()) m.group() else t
    }

    companion object {
        private const val PREFS = "videodroid"
        const val EXTRA_PICK_LOCAL = "pick_local"
        private const val KEY_QUALITY = "last_quality"
        private const val KEY_EXPORT_ON = "export_on"
        private const val KEY_ASPECT_MODE = "last_aspect_mode"
        private const val KEY_ASPECT_RATIO = "last_aspect_ratio"
        private const val KEY_TRIM_START = "trim_start_s"
        private const val KEY_TRIM_END = "trim_end_s"
        private const val KEY_CUSTOM_QUALITY = "custom_quality"
        private const val KEY_CUSTOM_EXPORT_ON = "custom_export_on"
        private const val KEY_CUSTOM_ASPECT_MODE = "custom_aspect_mode"
        private const val KEY_CUSTOM_ASPECT_RATIO = "custom_aspect_ratio"
        private const val KEY_LAST_OUTPUT = "last_output_uri"
        private const val KEY_BATTERY_ASKED = "battery_opt_asked"
        private const val REQ_POST_NOTIF = 2
        private const val REQ_OPEN_PAGE = 3
        private const val REQ_LOCAL_VIDEO = 4
    }
}
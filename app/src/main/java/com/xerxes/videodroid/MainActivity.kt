package com.xerxes.videodroid

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xerxes.videodroid.ui.JobSettings
import com.xerxes.videodroid.ui.Insets
import com.xerxes.videodroid.ui.MainUiState
import com.xerxes.videodroid.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), DownloadService.Listener, ShareSheetFragment.Listener,
    OptionsSheetFragment.Listener, ScanSheetFragment.Listener {

    private lateinit var urlInput: EditText
    private lateinit var dlQuality: Spinner
    private lateinit var exportSwitch: Switch
    private lateinit var exportOptions: LinearLayout
    private lateinit var aspectMode: Spinner
    private lateinit var aspectRatio: Spinner
    private lateinit var aspectRatioRow: LinearLayout
    private lateinit var trimSwitch: Switch
    private lateinit var trimOnOff: TextView
    private lateinit var trimFields: LinearLayout
    private lateinit var trimStart: EditText
    private lateinit var trimEnd: EditText
    private lateinit var goButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openPageButton: Button
    private lateinit var jobProgress: LinearProgressIndicator
    private lateinit var jobCard: com.google.android.material.card.MaterialCardView
    private lateinit var status: TextView
    private lateinit var statePill: TextView
    private lateinit var presetsExpanded: ChipGroup
    private var pendingStart = false
    private var suppressPersist = false

    private val vm: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    /** Single source of truth for the status line; mirrored into the TextView. */
    private var statusText: String
        get() = vm.state.value.statusText
        set(value) = vm.setStatus(value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // targetSdk 36 = enforced edge-to-edge; keep content out of the bars.
        SecureScreen.apply(window)
        Insets.applyTo(findViewById(R.id.mainWrapper))

        // Settings hands the "Local video" action back here, where the picker already lives.
        if (intent?.getBooleanExtra(EXTRA_PICK_LOCAL, false) == true) {
            intent.removeExtra(EXTRA_PICK_LOCAL)
            findViewById<View>(R.id.mainWrapper).post { runCatching { pickLocalVideo() } }
        }

        urlInput = findViewById(R.id.urlInput)
        dlQuality = findViewById(R.id.dlQuality)
        exportSwitch = findViewById(R.id.exportSwitch)
        exportOptions = findViewById(R.id.exportOptions)
        aspectMode = findViewById(R.id.aspectMode)
        aspectRatio = findViewById(R.id.aspectRatio)
        aspectRatioRow = findViewById(R.id.aspectRatioRow)
        trimSwitch = findViewById(R.id.trimSwitch)
        trimOnOff = findViewById(R.id.trimOnOff)
        trimFields = findViewById(R.id.trimFields)
        trimStart = findViewById(R.id.trimStart)
        trimEnd = findViewById(R.id.trimEnd)
        goButton = findViewById(R.id.goButton)
        cancelButton = findViewById(R.id.cancelButton)
        openPageButton = findViewById(R.id.openPageButton)
        jobProgress = findViewById(R.id.jobProgress)
        jobCard = findViewById(R.id.jobCard)
        status = findViewById(R.id.status)
        statePill = findViewById(R.id.statePill)
        presetsExpanded = findViewById(R.id.presetsExpanded)
        hideJobProgress()
        NavHelper.wire(this, R.id.tab_download)

        findViewById<Button>(R.id.scanPageButton).setOnClickListener {
            scanPageForVideos()
        }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        wireClipboardBanner()
        wireCrashBanner()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }

        findViewById<Chip>(R.id.presetX).setOnClickListener {
            applySocialPreset("X", exportOn = true, quality = "1080p", mode = "Fit", ratio = "Portrait 9:16")
        }
        findViewById<Chip>(R.id.presetTikTok).setOnClickListener {
            applySocialPreset("TikTok", exportOn = true, quality = "1080p", mode = "Fill", ratio = "Portrait 9:16")
        }
        findViewById<Chip>(R.id.presetShorts).setOnClickListener {
            applySocialPreset("Shorts", exportOn = true, quality = "1080p", mode = "Fill", ratio = "Portrait 9:16")
        }
        findViewById<Chip>(R.id.presetReels).setOnClickListener {
            applySocialPreset("Reels", exportOn = true, quality = "1080p", mode = "Fill", ratio = "Portrait 9:16")
        }
        findViewById<Chip>(R.id.presetYouTube).setOnClickListener {
            applySocialPreset("YouTube", exportOn = true, quality = "1080p", mode = "Original", ratio = "Portrait 9:16")
        }
        findViewById<Chip>(R.id.presetInstagram).setOnClickListener {
            applySocialPreset("Instagram", exportOn = true, quality = "1080p", mode = "Fill", ratio = "Portrait 4:5")
        }
        findViewById<Chip>(R.id.presetFacebook).setOnClickListener {
            applySocialPreset("Facebook", exportOn = true, quality = "1080p", mode = "Fit", ratio = "Landscape 16:9")
        }
        fun spinner(s: Spinner, values: Array<String>) {
            ArrayAdapter(this, R.layout.spinner_item, values).also {
                it.setDropDownViewResource(R.layout.spinner_dropdown_item)
                s.adapter = it
            }
        }
        val qualities = arrayOf("4K", "1080p", "720p", "480p")
        val modes = arrayOf("Original", "Fill", "Fit")
        val ratios = arrayOf(
            "Landscape 16:9", "Landscape 4:3", "Landscape 21:9",
            "Portrait 9:16", "Portrait 3:4", "Portrait 4:5",
            "Square 1:1",
        )
        spinner(dlQuality, qualities)
        spinner(aspectMode, modes)
        spinner(aspectRatio, ratios)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedQ = prefs.getString(KEY_QUALITY, "1080p") ?: "1080p"
        val savedMode = prefs.getString(KEY_ASPECT_MODE, "Original") ?: "Original"
        val savedRatio = prefs.getString(KEY_ASPECT_RATIO, "Portrait 9:16") ?: "Portrait 9:16"
        suppressPersist = true
        selectValue(dlQuality, savedQ, "1080p")
        exportSwitch.isChecked = prefs.getBoolean(KEY_EXPORT_ON, true)
        selectValue(aspectMode, savedMode, "Original")
        selectValue(aspectRatio, savedRatio, "Portrait 9:16")
        trimStart.setText(readTrimPref(prefs, KEY_TRIM_START).toString())
        trimEnd.setText(readTrimPref(prefs, KEY_TRIM_END).toString())
        suppressPersist = false
        applyExportUi(exportSwitch.isChecked)
        applyAspectModeUi()
        applyTrimOnOff(trimSwitch.isChecked)
        exportSwitch.setOnCheckedChangeListener { _, on ->
            applyExportUi(on)
            persistCurrent(custom = true)
            clearRecommendedNotice()
        }
        trimSwitch.setOnCheckedChangeListener { _, on ->
            applyTrimOnOff(on)
            persistCurrent(custom = true)
            clearRecommendedNotice()
        }

        val persistListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                persistCurrent(custom = true)
                clearRecommendedNotice()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        dlQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                persistCurrent(custom = true)
                clearRecommendedNotice()
                syncQualityPills(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        aspectMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyAspectModeUi()
                persistCurrent(custom = true)
                clearRecommendedNotice()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        aspectRatio.onItemSelectedListener = persistListener
        runCatching { wireV2Controls() }

        goButton.setOnClickListener {
            start()
        }
        cancelButton.setOnClickListener {
            cancelJob()
        }
        openPageButton.setOnClickListener {
            collapsePresets()
            openFailedPage()
        }
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) collapsePresets()
        }
        urlInput.setOnClickListener { collapsePresets() }
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s == null || s.toString().trim().isEmpty()) {
                    OpenPageActivity.clearJobUrl(this@MainActivity)
                }
            }
        })

        status.setOnClickListener {
            val uri = vm.state.value.resultUri ?: return@setOnClickListener
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

    /** Exit: warn first when a transfer is live, then really leave. */
    private fun confirmExit() {
        if (!jobIsRunning()) {
            exitApp()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit VideoDroid?")
            .setMessage(
                "A download is still in progress. Exiting stops it — anything left " +
                    "in the queue stays queued for the next time you open the app.",
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Exit") { _, _ -> exitApp() }
            .show()
    }

    /**
     * Leave for real. finishAffinity() alone only closes the UI, and the download
     * service is START_STICKY with a resume worker behind it — so all three have to
     * go, in this order, or the app comes back on its own.
     */
    private fun exitApp() {
        runCatching { stopService(Intent(this, DownloadService::class.java)) }
        runCatching {
            androidx.work.WorkManager.getInstance(applicationContext)
                .cancelUniqueWork(QueueResumeWorker.NAME)
        }
        finishAffinity()
        // 250 ms lets the activity finish cleanly before the process disappears, so
        // the exit does not look like a crash.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 250)
    }

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
        refreshBatteryButton()
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
        val url = firstUrl(raw).trim()
        // Stage 5b: a share can carry a caption, a hashtag or a bare host. Validate before
        // touching the queue, and say so instead of silently queueing nothing.
        if (!ClipboardLink.looksDownloadable(url)) {
            Toast.makeText(this, "No video link in that share", Toast.LENGTH_SHORT).show()
            return
        }
        urlInput.setText(url)
        if (url.contains("twitter.com", ignoreCase = true) || url.contains("://x.com", ignoreCase = true) ||
            url.contains("://www.x.com", ignoreCase = true)) {
            suppressPersist = true
            selectValue(aspectMode, "Fit")
            selectValue(aspectRatio, "Portrait 9:16")
            applyAspectModeUi()
            suppressPersist = false
        }
        // 1.9.4: a shared link goes straight to the queue using the current
        // settings — no sheet, no blocking, so parallel downloads can start
        // while the user is still in the other app.
        if (url.isNotBlank()) {
            persistCurrent(custom = false)
            launchJob(url)
        }
    }

    /** 1-5 simultaneous downloads, persisted and read by DownloadService. */
    private fun setupParallelSpinner() {
        val spinner = findViewById<Spinner>(R.id.parallelSpinner)
        val values = (1..5).map { "$it at a time" }
        ArrayAdapter(this, R.layout.spinner_item, values).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinner.adapter = it
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getInt(DownloadService.KEY_PARALLEL, DownloadService.DEFAULT_PARALLEL)
        spinner.setSelection((saved - 1).coerceIn(0, values.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(DownloadService.KEY_PARALLEL, position + 1).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Show whether Android is allowed to kill us, and offer to change it. */
    private fun refreshBatteryButton() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val btn = findViewById<Button>(R.id.batteryButton) ?: return
        btn.text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) "Battery: unrestricted ✓"
            else "Battery: restricted — tap to fix"
    }

    private fun toggleBatteryOptimisation() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            // Already exempt: send the user to the per-app battery screen so it
            // can be inspected or reverted later.
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }
            return
        }
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (runCatching { startActivity(i) }.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
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
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { statusText = "Paste a link or pick a local video"; return }

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
        if (SavedLinkIndex.contains(this, url)) {
            Haptics.tick(this)
            showAlreadyInLibrary(url)
            return
        }

        launchJob(url)
    }

    /**
     * Generic page scan: fetch once, parse, probe sizes off the UI thread, let the owner choose.
     * Nothing downloads until a row is confirmed, and every pick still passes the saved-link
     * duplicate gate before it reaches the queue.
     */
    private fun scanPageForVideos() {
        val url = urlInput.text.toString().trim()
        if (!ClipboardLink.looksDownloadable(url)) {
            statusText = "Paste a page link first"
            return
        }
        statusText = "Scanning page…"
        Haptics.tick(this)
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { StaticPageFetcher.fetch(this@MainActivity, url) }.getOrNull()
            }
            if (page == null || page.html.isBlank()) {
                statusText = ErrorText.human(page?.error ?: "Couldn't load that page")
                return@launch
            }
            var found = withContext(Dispatchers.Default) {
                PageScanner.scan(page.html, page.finalUrl.ifBlank { url })
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
    private fun showAlreadyInLibrary(url: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Already in library")
            .setMessage("This link is already saved on this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download again anyway") { _, _ -> launchJob(url) }
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
            launchJob(urlInput.text.toString().trim())
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

    private fun launchJob(url: String, localPath: String? = null) {
        if (url.isEmpty() && localPath.isNullOrBlank()) { statusText = "Paste a link or pick a video"; return }

        val dlQ = dlQuality.selectedItem.toString()
        val exportOn = exportSwitch.isChecked
        val aspect = if (exportOn) currentAspectToken() else "original"
        val trimOn = if (exportOn) trimSwitch.isChecked else false
        val prefsNow = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tStart = FormatOptions.parseTrimSeconds(
            trimStart.text.toString(),
            readTrimPref(prefsNow, KEY_TRIM_START),
        )
        val tEnd = FormatOptions.parseTrimSeconds(
            trimEnd.text.toString(),
            readTrimPref(prefsNow, KEY_TRIM_END),
        )

        persistCurrent(custom = false)

        vm.startJob(
            url = url,
            localPath = localPath,
            settings = JobSettings(
                dlQ, exportOn, aspect, trimOn, tStart, tEnd,
                audioOnly = prefsNow.getBoolean("audio_only", false),
            ),
        )
    }

    private fun openOptionsSheet() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        OptionsSheetFragment.newInstance(
            quality = dlQuality.selectedItem?.toString() ?: "1080p",
            exportOn = exportSwitch.isChecked,
            mode = aspectMode.selectedItem?.toString() ?: "Original",
            ratio = aspectRatio.selectedItem?.toString() ?: "Portrait 9:16",
            trimOn = trimSwitch.isChecked,
            trimStart = trimStart.text.toString().toIntOrNull()
                ?: readTrimPref(prefs, KEY_TRIM_START),
            trimEnd = trimEnd.text.toString().toIntOrNull()
                ?: readTrimPref(prefs, KEY_TRIM_END),
        ).show(supportFragmentManager, OptionsSheetFragment.TAG)
    }

    /** Values from the sheet are written back into the real widgets, then persisted. */
    override fun onOptionsApplied(
        quality: String,
        exportOn: Boolean,
        mode: String,
        ratio: String,
        trimOn: Boolean,
        trimStart: Int,
        trimEnd: Int,
    ) {
        suppressPersist = true
        selectValue(dlQuality, quality, "1080p")
        exportSwitch.isChecked = exportOn
        selectValue(aspectMode, mode, "Original")
        selectValue(aspectRatio, ratio, "Portrait 9:16")
        trimSwitch.isChecked = trimOn
        this.trimStart.setText(trimStart.toString())
        this.trimEnd.setText(trimEnd.toString())
        suppressPersist = false

        applyExportUi(exportOn)
        applyAspectModeUi()
        applyTrimOnOff(trimOn)
        persistCurrent(custom = true)
        clearRecommendedNotice()
        syncQualityPills(dlQuality.selectedItemPosition)
    }

    private fun persistCurrent(custom: Boolean) {
        if (suppressPersist) return
        val dlQ = if (::dlQuality.isInitialized && dlQuality.selectedItem != null) {
            dlQuality.selectedItem.toString()
        } else return
        val exportOn = exportSwitch.isChecked
        val mode = if (::aspectMode.isInitialized && aspectMode.selectedItem != null) {
            aspectMode.selectedItem.toString()
        } else return
        val ratio = if (::aspectRatio.isInitialized && aspectRatio.selectedItem != null) {
            aspectRatio.selectedItem.toString()
        } else return
        val ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, dlQ)
            .putBoolean(KEY_EXPORT_ON, exportOn)
            .putString(KEY_ASPECT_MODE, mode)
            .putString(KEY_ASPECT_RATIO, ratio)
            .putInt(
                KEY_TRIM_START,
                FormatOptions.parseTrimSeconds(
                    trimStart.text.toString(),
                    readTrimPref(getSharedPreferences(PREFS, MODE_PRIVATE), KEY_TRIM_START),
                ),
            )
            .putInt(
                KEY_TRIM_END,
                FormatOptions.parseTrimSeconds(
                    trimEnd.text.toString(),
                    readTrimPref(getSharedPreferences(PREFS, MODE_PRIVATE), KEY_TRIM_END),
                ),
            )
        if (custom && !isRecommendedSelection()) {
            ed.putString(KEY_CUSTOM_QUALITY, dlQ)
                .putBoolean(KEY_CUSTOM_EXPORT_ON, exportOn)
                .putString(KEY_CUSTOM_ASPECT_MODE, mode)
                .putString(KEY_CUSTOM_ASPECT_RATIO, ratio)
        }
        ed.apply()
    }

    private fun isRecommendedSelection(): Boolean {
        val q = dlQuality.selectedItem?.toString() ?: return false
        val mode = aspectMode.selectedItem?.toString() ?: return false
        return q == "1080p" && mode.equals("Original", ignoreCase = true) && !trimSwitch.isChecked
    }

    private fun currentAspectToken(): String {
        val mode = aspectMode.selectedItem?.toString() ?: "Original"
        val ratio = aspectRatio.selectedItem?.toString() ?: "Portrait 9:16"
        return aspectToken(mode, ratio)
    }

    /** Mode + grouped ratio labels → FormatWorker tokens (original / crop:W:H / pad:W:H). */
    private fun aspectToken(mode: String, ratioLabel: String): String {
        val m = mode.trim()
        if (m.equals("Original", ignoreCase = true)) return "original"
        val spec = ratioLabel.trim().substringAfterLast(' ').replace(" ", "")
        if (m.equals("Fill", ignoreCase = true)) return "crop:$spec"
        if (m.equals("Fit", ignoreCase = true)) return "pad:$spec"
        return spec
    }

    private fun clearRecommendedNotice() {
        if (suppressPersist) return
        val t = vm.state.value.statusText
        if (t.startsWith("Recommended:")) statusText = ""
    }

    private fun applyRecommended() {
        persistCurrent(custom = true)
        suppressPersist = true
        selectValue(dlQuality, "1080p")
        selectValue(aspectMode, "Original")
        applyAspectModeUi()
        trimSwitch.isChecked = false
        applyTrimOnOff(false)
        suppressPersist = false
        persistCurrent(custom = false)
        statusText = "Recommended: 1080p / original / trim off"
    }

    private fun applyMyLast() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val q = p.getString(KEY_CUSTOM_QUALITY, null) ?: return
        suppressPersist = true
        selectValue(dlQuality, q, "1080p")
        exportSwitch.isChecked = p.getBoolean(KEY_CUSTOM_EXPORT_ON, exportSwitch.isChecked)
        selectValue(aspectMode, p.getString(KEY_CUSTOM_ASPECT_MODE, "Original") ?: "Original", "Original")
        selectValue(aspectRatio, p.getString(KEY_CUSTOM_ASPECT_RATIO, "Portrait 9:16") ?: "Portrait 9:16", "Portrait 9:16")
        suppressPersist = false
        persistCurrent(custom = false)
        applyExportUi(exportSwitch.isChecked)
        applyAspectModeUi()
        statusText = "Restored your last settings"
    }


    private val qualityPillIds = intArrayOf(R.id.q4k, R.id.q1080, R.id.q720, R.id.q480)

    private fun syncQualityPills(position: Int) {
        val group = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.qualityPills,
        ) ?: return
        val target = qualityPillIds.getOrNull(position) ?: R.id.q1080
        if (group.checkedButtonId != target) group.check(target)
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

    private fun wireV2Controls() {
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.qualityPills,
        )?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = qualityPillIds.indexOf(checkedId)
            if (idx >= 0 && dlQuality.selectedItemPosition != idx) dlQuality.setSelection(idx)
        }
        syncQualityPills(dlQuality.selectedItemPosition)

        findViewById<View>(R.id.pasteButton)?.setOnClickListener {
            val link = clipboardUrl()
            if (link != null) {
                urlInput.setText(link)
                urlInput.setSelection(urlInput.text.length)
            } else {
                Toast.makeText(this, "No link in clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // The Options button opens the bottom sheet.
        findViewById<View>(R.id.optionsButton)?.setOnClickListener {
            openOptionsSheet()
        }
    }

    /** Offer a copied link as soon as the screen becomes visible again. */
    // ── Local crash log banner ───────────────────────────────────────────────────
    /**
     * One-time notice that the previous run died, with the record already on disk. Nothing is
     * uploaded; the banner only points at Settings → Crash logs (or opens it directly).
     */
    private fun wireCrashBanner() {
        val banner = findViewById<View>(R.id.crashBanner) ?: return
        if (!CrashLog.hasPending(this)) return
        banner.visibility = View.VISIBLE
        findViewById<Button>(R.id.crashBannerView).setOnClickListener {
            banner.visibility = View.GONE
            CrashLog.clearPending(this)
            runCatching { startActivity(Intent(this, CrashLogActivity::class.java)) }
        }
        findViewById<Button>(R.id.crashBannerDismiss).setOnClickListener {
            banner.visibility = View.GONE
            CrashLog.clearPending(this)
        }
    }

    // ── Stage 2: clipboard detect ────────────────────────────────────────────────
    private var pendingClipboardUrl: String? = null
    private var dismissedClipboardUrl: String? = null

    private fun wireClipboardBanner() {
        findViewById<Button>(R.id.clipboardBannerGo).setOnClickListener {
            val url = pendingClipboardUrl ?: return@setOnClickListener
            urlInput.setText(url)
            hideClipboardBanner()
            persistCurrent(custom = false)
            launchJob(url)
        }
        findViewById<Button>(R.id.clipboardBannerClose).setOnClickListener {
            dismissedClipboardUrl = pendingClipboardUrl
            hideClipboardBanner()
        }
    }

    private fun hideClipboardBanner() {
        pendingClipboardUrl = null
        findViewById<View>(R.id.clipboardBanner)?.visibility = View.GONE
    }

    /**
     * Show "Download this?" when the clipboard holds a link we have not offered yet.
     * Never auto-starts a download: the owner taps Download, so a stray clipboard URL
     * cannot burn data on ship Wi-Fi.
     */
    private fun checkClipboardBanner() {
        if (!::urlInput.isInitialized) return
        val url = clipboardUrl()
        if (url == null) { hideClipboardBanner(); return }
        if (url == dismissedClipboardUrl) { hideClipboardBanner(); return }
        if (url == urlInput.text?.toString()?.trim()) { hideClipboardBanner(); return }
        if (!looksLikeMediaLink(url)) { hideClipboardBanner(); return }
        val identity = MediaIdentity.canonicalUrl(url)
        val alreadyKnown = DownloadQueue.all(this).any {
            MediaIdentity.canonicalUrl(it.url) == identity
        }
        if (alreadyKnown) { hideClipboardBanner(); return }
        pendingClipboardUrl = url
        findViewById<TextView>(R.id.clipboardBannerText)?.text = url
        findViewById<View>(R.id.clipboardBanner)?.visibility = View.VISIBLE
    }

    /** Cheap sanity gate; the rule itself lives in [ClipboardLink] so it can be unit tested. */
    private fun looksLikeMediaLink(url: String): Boolean = ClipboardLink.looksDownloadable(url)

    private fun autoFillFromClipboard() {
        try {
            if (!::urlInput.isInitialized) return
            if (urlInput.text.isNotBlank()) return
            clipboardUrl()?.let { urlInput.setText(it) }
        } catch (t: Throwable) {
            // never block startup on a clipboard read
        }
    }

    private fun applySocialPreset(name: String, exportOn: Boolean, quality: String, mode: String, ratio: String) {
        suppressPersist = true
        selectValue(dlQuality, quality)
        exportSwitch.isChecked = exportOn
        selectValue(aspectMode, mode)
        selectValue(aspectRatio, ratio)
        trimSwitch.isChecked = false
        applyExportUi(exportOn)
        applyAspectModeUi()
        applyTrimOnOff(false)
        suppressPersist = false
        persistCurrent(custom = true)
        clearRecommendedNotice()
        if (!jobIsRunning()) {
            statusText = "$name: $quality / $mode${if (mode == "Original") "" else " $ratio"} / trim off"
        } else {
            Toast.makeText(this, "$name preset applied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun expandPresets() {
        // Stage 1: the preset row is always visible; nothing to expand.
    }

    private fun collapsePresets() {
        // Stage 1: no collapsed preset row and no "More" list any more — kept as a no-op so
        // the existing call sites stay harmless and self-documenting.
    }

    private fun showChangelog() {
        val text = loadChangelogText()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Changelog")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun loadChangelogText(): String {
        return try {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            BAKED_CHANGELOG
        }
    }

    private fun selectValue(s: Spinner, value: String, fallback: String? = null) {
        val adapter = s.adapter ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == value) {
                s.setSelection(i)
                return
            }
        }
        val fb = fallback ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == fb) {
                s.setSelection(i)
                return
            }
        }
    }

    private fun cancelJob() {
        vm.cancel()
    }

    private fun applyTrimOnOff(on: Boolean) {
        trimOnOff.text = if (on) "On" else "Off"
        trimFields.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun applyAspectModeUi() {
        if (!::aspectMode.isInitialized || !::aspectRatioRow.isInitialized) return
        val original = aspectMode.selectedItem?.toString().equals("Original", ignoreCase = true)
        aspectRatioRow.visibility = if (original) View.GONE else View.VISIBLE
    }

    private fun applyExportUi(on: Boolean) {
        exportOptions.visibility = if (on) View.VISIBLE else View.GONE
        refreshGoButton()
    }

    private fun queueBusy(): Boolean = vm.state.value.busy || DownloadQueue.size(this) > 0

    private fun refreshGoButton() {
        goButton.text = when {
            queueBusy() -> "Add to queue"
            exportSwitch.isChecked -> "Download & Convert"
            else -> "Download"
        }
    }

    /** Single place that pushes ViewModel state into the widgets. */
    private fun render(s: MainUiState) {
        if (status.text?.toString() != s.statusText) status.text = s.statusText
        // "ready" used to be a hardcoded label, so the header claimed everything was fine
        // even with a queue full of failed rows. Say what is actually true.
        val label = when {
            s.progressVisible && s.waiting > 0 -> "working · ${s.waiting} waiting"
            s.progressVisible -> "working"
            s.busy && s.waiting > 0 -> "working · ${s.waiting} waiting"
            s.busy -> "working"
            s.queueCount > 0 -> "${s.queueCount} in queue — tap Queue"
            else -> "ready"
        }
        if (statePill.text?.toString() != label) statePill.text = label
        jobCard.visibility =
            if (s.statusText.isNotEmpty() || s.progressVisible) View.VISIBLE else View.GONE
        goButton.isEnabled = true
        goButton.text = when {
            s.busy -> "Add to queue"
            exportSwitch.isChecked -> "Download & Convert"
            else -> "Download"
        }
        cancelButton.visibility = if (s.busy) View.VISIBLE else View.GONE
        openPageButton.visibility = if (s.openFailedVisible) View.VISIBLE else View.GONE
        if (!s.progressVisible) {
            hideJobProgress()
            return
        }
        jobProgress.visibility = View.VISIBLE
        if (s.progressIndeterminate || s.progressPercent == null) {
            jobProgress.isIndeterminate = true
        } else {
            if (jobProgress.isIndeterminate) jobProgress.isIndeterminate = false
            jobProgress.setProgressCompat(s.progressPercent, true)
        }
    }

    private fun hideJobProgress() {
        if (!::jobProgress.isInitialized) return
        jobProgress.isIndeterminate = false
        jobProgress.setProgressCompat(0, false)
        jobProgress.visibility = View.GONE
    }

    private fun openFailedPage() {
        val url = sequenceOf(
            vm.state.value.lastFailUrl.orEmpty(),
            OpenPageActivity.loadJobUrl(this),
            urlInput.text.toString().trim(),
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val i = Intent(this, OpenPageActivity::class.java)
        if (url.isNotEmpty()) {
            i.putExtra(OpenPageActivity.EXTRA_URL, url)
        }
        startActivityForResult(i, REQ_OPEN_PAGE)
    }

    override fun onStatus(msg: String) {
        vm.onServiceStatus(msg)
    }

    /** Result of the share bottom sheet: apply the pick, then queue. */
    override fun onShareConfirmed(url: String, quality: String, exportOn: Boolean) {
        suppressPersist = true
        selectValue(dlQuality, quality)
        exportSwitch.isChecked = exportOn
        applyExportUi(exportOn)
        suppressPersist = false
        persistCurrent(custom = true)
        launchJob(url)
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

    private fun copyLog() {
        val pkg = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Throwable) {
            null
        }
        val opts = JobDiag.lastOpts
        val exportOn = opts?.exportEnabled ?: exportSwitch.isChecked
        val quality = opts?.dlQuality ?: (dlQuality.selectedItem?.toString() ?: "?")
        val aspect = opts?.aspect ?: aspectRatio.selectedItem?.toString() ?: "?"
        val trimOn = opts?.trimEnabled ?: trimSwitch.isChecked
        val tStart = FormatOptions.clampTrimSeconds(
            opts?.trimStart ?: FormatOptions.parseTrimSeconds(trimStart.text.toString()),
        )
        val tEnd = FormatOptions.clampTrimSeconds(
            opts?.trimEnd ?: FormatOptions.parseTrimSeconds(trimEnd.text.toString()),
        )
        val qSize = DownloadQueue.size(this)
        val durKnown = JobDiag.durationKnown
        val durLine = when (durKnown) {
            true -> "duration known: yes (${JobDiag.durationValue}s)"
            false -> "duration known: no"
            null -> "duration known: unknown (job not past probe)"
        }
        val lines = ArrayList<String>()
        lines.add("VideoDroid ${pkg?.versionName ?: "?"} / ${pkg?.versionCode ?: "?"}")
        lines.add("export: ${if (exportOn) "on" else "off"}  quality: $quality  aspect: $aspect")
        lines.add("trim: ${if (trimOn) "on" else "off"}  start=${tStart}s  end=${tEnd}s")
        lines.add("queue size: $qSize")
        lines.add("current: ${JobDiag.redact(vm.state.value.statusText.ifEmpty { JobDiag.lastStatusLine })}")
        lines.add("")
        lines.add("last statuses:")
        val hist = JobDiag.lastStatuses(30)
        if (hist.isEmpty()) lines.add("(none)") else lines.addAll(hist)
        lines.add("")
        lines.add("ffmpeg rc: ${JobDiag.lastFfmpegRc.ifEmpty { "(none)" }}")
        lines.add("ffmpeg tail:")
        lines.add(JobDiag.lastFfmpegTail.ifEmpty { "(none)" })
        lines.add("")
        lines.add("last exception: ${JobDiag.lastException.ifEmpty { "(none)" }}")
        lines.add(durLine)
        lines.add("")
        lines.add(
            "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
        )
        // Per-row reasons: the Diagnostics view showed only the *current* job, so after a
        // restart every failed row's reason was invisible.
        val rows = DownloadQueue.all(this)
        if (rows.isNotEmpty()) {
            lines.add("")
            lines.add("queue rows (${rows.size}):")
            for (j in rows.take(25)) {
                val what = MediaIdentity.canonicalUrl(j.url) ?: j.localPath.takeLast(60)
                lines.add(
                    "- $what | failed=${j.failed} paused=${j.paused} " +
                        "attempts=${j.attempts} progress=${j.progress}% | " +
                        j.failReason.ifEmpty { "-" },
                )
            }
        }
        // Persisted history survives a process death; the ring buffer above does not.
        val saved = JobDiag.history(this, 6000)
        if (saved.isNotBlank()) {
            lines.add("")
            lines.add("saved job log (filesDir/diag.log):")
            lines.add(saved)
        }
        val blob = JobDiag.redact(lines.joinToString("\n"))
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("videodroid-log", blob))
        statusText = "Log copied"
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
        private val JOB_PCT_RE = Regex("""(?:Downloading|Converting(?: \([^)]+\))?|Trimming) (\d+)%""")
        private const val BAKED_CHANGELOG = """## 1.6.1 (versionCode 28)
- best = 1080 cap; new 4K option
- Export Off = original only (hide Fill/Fit/trim)
- Check update: Toast only while a job is running
- Encode: h264_mediacodec 16M/24M (bufsize 2x); mpeg4 -q:v 4
- In-app changelog; social presets override all settings

## 1.6.0 (versionCode 27)
- Fill/Fit + grouped ratios; short-title filenames

## 1.5.9 (versionCode 26)
- Job status quality/aspect/trim; Check update Tailscale fallback
"""
    }
}

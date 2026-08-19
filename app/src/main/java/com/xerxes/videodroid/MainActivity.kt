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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), DownloadService.Listener {

    private lateinit var urlInput: EditText
    private lateinit var dlQuality: Spinner
    private lateinit var exportSwitch: Switch
    private lateinit var exportOptions: LinearLayout
    private lateinit var moreSection: LinearLayout
    private lateinit var moreToggle: TextView
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
    private lateinit var status: TextView
    private var busy = false
    private var lastResultUri: Uri? = null
    private var pendingStart = false
    private var suppressPersist = false
    private var lastFailUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        dlQuality = findViewById(R.id.dlQuality)
        exportSwitch = findViewById(R.id.exportSwitch)
        exportOptions = findViewById(R.id.exportOptions)
        moreSection = findViewById(R.id.moreSection)
        moreToggle = findViewById(R.id.moreToggle)
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
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.loginXButton).setOnClickListener {
            startActivity(Intent(this, XLoginActivity::class.java))
        }
        findViewById<Button>(R.id.clearLoginButton).setOnClickListener {
            XCookies.clear(this)
            status.text = "X login cleared"
        }
        findViewById<Button>(R.id.copyLogButton).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener { checkUpdateNow() }
        findViewById<Button>(R.id.changelogButton).setOnClickListener { showChangelog() }
        findViewById<Button>(R.id.presetX).setOnClickListener {
            applySocialPreset("X", exportOn = true, quality = "best", mode = "Fit", ratio = "Portrait 9:16")
        }
        findViewById<Button>(R.id.presetTikTok).setOnClickListener {
            applySocialPreset("TikTok / Shorts / Reels", exportOn = true, quality = "best", mode = "Fill", ratio = "Portrait 9:16")
        }
        findViewById<Button>(R.id.presetYouTube).setOnClickListener {
            applySocialPreset("YouTube", exportOn = true, quality = "best", mode = "Original", ratio = "Portrait 9:16")
        }
        findViewById<Button>(R.id.presetInstagram).setOnClickListener {
            applySocialPreset("Instagram", exportOn = true, quality = "best", mode = "Fill", ratio = "Portrait 4:5")
        }
        findViewById<Button>(R.id.presetFacebook).setOnClickListener {
            applySocialPreset("Facebook", exportOn = true, quality = "best", mode = "Fit", ratio = "Landscape 16:9")
        }

        moreToggle.setOnClickListener {
            val open = moreSection.visibility != View.VISIBLE
            moreSection.visibility = if (open) View.VISIBLE else View.GONE
            moreToggle.text = if (open) "More ▾" else "More ▸"
        }

        fun spinner(s: Spinner, values: Array<String>) {
            ArrayAdapter(this, R.layout.spinner_item, values).also {
                it.setDropDownViewResource(R.layout.spinner_dropdown_item)
                s.adapter = it
            }
        }
        val qualities = arrayOf("best", "4K", "1080p", "720p", "480p")
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
        val savedQ = prefs.getString(KEY_QUALITY, "720p") ?: "720p"
        val qi = qualities.indexOf(savedQ).let { if (it >= 0) it else 2 }
        val savedMode = prefs.getString(KEY_ASPECT_MODE, "Original") ?: "Original"
        val savedRatio = prefs.getString(KEY_ASPECT_RATIO, "Portrait 9:16") ?: "Portrait 9:16"
        suppressPersist = true
        dlQuality.setSelection(qi)
        exportSwitch.isChecked = prefs.getBoolean(KEY_EXPORT_ON, true)
        selectValue(aspectMode, savedMode)
        selectValue(aspectRatio, savedRatio)
        trimStart.setText(prefs.getInt(KEY_TRIM_START, 1).toString())
        trimEnd.setText(prefs.getInt(KEY_TRIM_END, 1).toString())
        suppressPersist = false
        lastFailUrl = OpenPageActivity.loadJobUrl(this).ifEmpty { null }
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
        dlQuality.onItemSelectedListener = persistListener
        aspectMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyAspectModeUi()
                persistCurrent(custom = true)
                clearRecommendedNotice()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        aspectRatio.onItemSelectedListener = persistListener

        findViewById<Button>(R.id.recommendedButton).setOnClickListener { applyRecommended() }
        findViewById<Button>(R.id.myLastButton).setOnClickListener { applyMyLast() }

        goButton.setOnClickListener { start() }
        cancelButton.setOnClickListener { cancelJob() }
        openPageButton.setOnClickListener { openFailedPage() }
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
            val uri = lastResultUri ?: return@setOnClickListener
            try {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(view)
            } catch (_: Throwable) {
                status.text = "Could not open file"
            }
        }

        maybeAskIgnoreBattery()
        handleIncomingShare(intent, enqueueIfBusy = true)

        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }

        Thread {
            try {
                val pkg = packageManager.getPackageInfo(packageName, 0)
                UpdateChecker.check(this, pkg.versionName ?: "0.0", userInitiated = false)
            } catch (_: Throwable) { }
        }.start()
    }

    fun jobIsRunning(): Boolean = busy || DownloadQueue.size(this) > 0

    fun showUpdateStatus(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (!jobIsRunning()) status.text = msg
        }
    }

    private fun checkUpdateNow() {
        if (jobIsRunning()) {
            Toast.makeText(this, "Checking update…", Toast.LENGTH_SHORT).show()
        } else {
            status.text = "Checking update…"
        }
        Thread {
            try {
                val pkg = packageManager.getPackageInfo(packageName, 0)
                UpdateChecker.check(this, pkg.versionName ?: "0.0", userInitiated = true)
            } catch (t: Throwable) {
                showUpdateStatus("Update check failed: ${t.javaClass.simpleName}")
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        DownloadService.listener = this
        val q = DownloadQueue.size(this)
        val corrupt = DownloadQueue.consumeCorruptNotice()
        if (corrupt != null) {
            status.text = corrupt
        } else if (q > 0) {
            setBusy(true)
            val wait = DownloadQueue.waiting(this)
            status.text = if (wait > 0) "Queued ($wait)\nWorking..." else "Working..."
        }
    }

    override fun onStop() {
        if (DownloadService.listener === this) DownloadService.listener = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent, enqueueIfBusy = true)
    }

    private fun handleIncomingShare(intent: Intent?, enqueueIfBusy: Boolean) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = firstUrl(raw)
        urlInput.setText(url)
        if (url.contains("twitter.com", ignoreCase = true) || url.contains("://x.com", ignoreCase = true) ||
            url.contains("://www.x.com", ignoreCase = true)) {
            suppressPersist = true
            selectValue(aspectMode, "Fit")
            selectValue(aspectRatio, "Portrait 9:16")
            applyAspectModeUi()
            suppressPersist = false
        }
        if (enqueueIfBusy && (busy || DownloadQueue.size(this) > 0) && url.isNotBlank()) {
            launchJob(url)
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

    private fun start() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { status.text = "Paste a link first"; return }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                pendingStart = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
                return
            }
        }

        launchJob(url)
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
    }

    private fun launchJob(url: String) {
        if (url.isEmpty()) { status.text = "Paste a link first"; return }

        val dlQ = dlQuality.selectedItem.toString()
        val exportOn = exportSwitch.isChecked
        val aspect = if (exportOn) currentAspectToken() else "original"
        val trimOn = if (exportOn) trimSwitch.isChecked else false
        val tStart = trimStart.text.toString().toIntOrNull() ?: 1
        val tEnd = trimEnd.text.toString().toIntOrNull() ?: 1

        persistCurrent(custom = false)

        val opts = FormatOptions(dlQ, 0, aspect, 0, 3, trimOn, tStart, tEnd, exportOn)
        lastResultUri = null
        lastFailUrl = url
        OpenPageActivity.storeJobUrl(this, url)
        openPageButton.visibility = View.GONE
        setBusy(true)
        val already = DownloadQueue.size(this)
        status.text = if (already > 0) "Queued (${already})\nStarting..." else "Starting..."
        DownloadService.start(this, url, opts)
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
            .putInt(KEY_TRIM_START, trimStart.text.toString().toIntOrNull() ?: 1)
            .putInt(KEY_TRIM_END, trimEnd.text.toString().toIntOrNull() ?: 1)
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
        return q == "best" && mode.equals("Original", ignoreCase = true) && !trimSwitch.isChecked
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
        val t = status.text?.toString() ?: return
        if (t.startsWith("Recommended:")) status.text = ""
    }

    private fun applyRecommended() {
        persistCurrent(custom = true)
        suppressPersist = true
        selectValue(dlQuality, "best")
        selectValue(aspectMode, "Original")
        applyAspectModeUi()
        trimSwitch.isChecked = false
        applyTrimOnOff(false)
        suppressPersist = false
        persistCurrent(custom = false)
        status.text = "Recommended: best / original / trim off"
    }

    private fun applyMyLast() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val q = p.getString(KEY_CUSTOM_QUALITY, null) ?: return
        suppressPersist = true
        selectValue(dlQuality, q)
        exportSwitch.isChecked = p.getBoolean(KEY_CUSTOM_EXPORT_ON, exportSwitch.isChecked)
        selectValue(aspectMode, p.getString(KEY_CUSTOM_ASPECT_MODE, aspectMode.selectedItem?.toString() ?: "Original") ?: "Original")
        selectValue(aspectRatio, p.getString(KEY_CUSTOM_ASPECT_RATIO, aspectRatio.selectedItem?.toString() ?: "Portrait 9:16") ?: "Portrait 9:16")
        suppressPersist = false
        persistCurrent(custom = false)
        applyExportUi(exportSwitch.isChecked)
        applyAspectModeUi()
        status.text = "Restored your last settings"
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
            status.text = "$name: $quality / $mode${if (mode == "Original") "" else " $ratio"} / trim off"
        } else {
            Toast.makeText(this, "$name preset applied", Toast.LENGTH_SHORT).show()
        }
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

    private fun selectValue(s: Spinner, value: String) {
        val adapter = s.adapter ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == value) {
                s.setSelection(i)
                return
            }
        }
    }

    private fun cancelJob() {
        status.text = "Cancelling..."
        openPageButton.visibility = View.GONE
        DownloadService.cancel(this)
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
        goButton.text = if (on) "Download & Convert" else "Download"
    }

    private fun setBusy(on: Boolean) {
        busy = on
        goButton.isEnabled = true
        goButton.text = if (exportSwitch.isChecked) "Download & Convert" else "Download"
        cancelButton.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun openFailedPage() {
        val url = sequenceOf(
            lastFailUrl.orEmpty(),
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
        runOnUiThread { status.text = msg }
    }

    override fun onFinished(result: FormatResult) {
        runOnUiThread {
            val remaining = DownloadQueue.size(this)
            setBusy(remaining > 0)
            val prefix = if (remaining > 0) {
                val w = DownloadQueue.waiting(this)
                if (w > 0) "Queued ($w)\n" else ""
            } else ""
            if (result.ok && result.uri != null) {
                lastResultUri = result.uri
                lastFailUrl = null
                OpenPageActivity.clearJobUrl(this)
                openPageButton.visibility = View.GONE
                status.text = prefix + "Saved. Tap to open\n${result.uri}"
            } else {
                lastResultUri = null
                val cancelled = result.message.equals("Cancelled", ignoreCase = true)
                status.text = prefix + result.message
                openPageButton.visibility = if (cancelled || remaining > 0) View.GONE else View.VISIBLE
            }
        }
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
        val tStart = opts?.trimStart ?: (trimStart.text.toString().toIntOrNull() ?: 1)
        val tEnd = opts?.trimEnd ?: (trimEnd.text.toString().toIntOrNull() ?: 1)
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
        lines.add("current: ${JobDiag.redact(status.text?.toString() ?: JobDiag.lastStatusLine)}")
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
        val blob = JobDiag.redact(lines.joinToString("\n"))
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("videodroid-log", blob))
        status.text = "Log copied"
    }

    private fun firstUrl(t: String): String {
        val m = Pattern.compile("https?://\\S+").matcher(t)
        return if (m.find()) m.group() else t
    }

    companion object {
        private const val PREFS = "videodroid"
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
        private const val KEY_BATTERY_ASKED = "battery_opt_asked"
        private const val REQ_POST_NOTIF = 2
        private const val REQ_OPEN_PAGE = 3
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

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
    private lateinit var aspectRatio: Spinner
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
        aspectRatio = findViewById(R.id.aspectRatio)
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
        val qualities = arrayOf("best", "1080p", "720p", "480p")
        spinner(dlQuality, qualities)
        spinner(aspectRatio, arrayOf(
            "Original", "Fill 4:3", "Fill 16:9", "Fill 1:1",
            "Fit 4:3", "Fit 16:9", "Fit 9:16"
        ))

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedQ = prefs.getString(KEY_QUALITY, "720p") ?: "720p"
        val qi = qualities.indexOf(savedQ).let { if (it >= 0) it else 2 }
        suppressPersist = true
        dlQuality.setSelection(qi)
        exportSwitch.isChecked = prefs.getBoolean(KEY_EXPORT_ON, true)
        aspectRatio.setSelection(prefs.getInt(KEY_ASPECT, 0))
        trimStart.setText(prefs.getInt(KEY_TRIM_START, 1).toString())
        trimEnd.setText(prefs.getInt(KEY_TRIM_END, 1).toString())
        suppressPersist = false
        applyExportUi(exportSwitch.isChecked)
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
        aspectRatio.onItemSelectedListener = persistListener

        findViewById<Button>(R.id.recommendedButton).setOnClickListener { applyRecommended() }
        findViewById<Button>(R.id.myLastButton).setOnClickListener { applyMyLast() }

        goButton.setOnClickListener { start() }
        cancelButton.setOnClickListener { cancelJob() }
        openPageButton.setOnClickListener { openFailedPage() }

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

    fun showUpdateStatus(msg: String) {
        runOnUiThread { status.text = msg }
    }

    private fun checkUpdateNow() {
        status.text = "Checking update…"
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
        if (q > 0) {
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
        val aspect = aspectToken(aspectRatio.selectedItem.toString())
        val trimOn = trimSwitch.isChecked
        val tStart = trimStart.text.toString().toIntOrNull() ?: 1
        val tEnd = trimEnd.text.toString().toIntOrNull() ?: 1

        persistCurrent(custom = false)

        val exportOn = exportSwitch.isChecked
        val opts = FormatOptions(dlQ, 0, aspect, 0, 6, trimOn, tStart, tEnd, exportOn)
        lastResultUri = null
        lastFailUrl = url
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
        val aPos = aspectRatio.selectedItemPosition
        val ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, dlQ)
            .putBoolean(KEY_EXPORT_ON, exportOn)
            .putInt(KEY_ASPECT, aPos)
            .putInt(KEY_TRIM_START, trimStart.text.toString().toIntOrNull() ?: 1)
            .putInt(KEY_TRIM_END, trimEnd.text.toString().toIntOrNull() ?: 1)
        if (custom && !isRecommendedSelection()) {
            ed.putString(KEY_CUSTOM_QUALITY, dlQ)
                .putBoolean(KEY_CUSTOM_EXPORT_ON, exportOn)
                .putInt(KEY_CUSTOM_ASPECT, aPos)
        }
        ed.apply()
    }

    private fun isRecommendedSelection(): Boolean {
        val q = dlQuality.selectedItem?.toString() ?: return false
        val a = aspectRatio.selectedItem?.toString() ?: return false
        return q == "best" && a.equals("Original", ignoreCase = true) && !trimSwitch.isChecked
    }

    /** Spinner labels → FormatWorker tokens (original / crop:X / pad:X). */
    private fun aspectToken(label: String): String {
        val t = label.trim()
        if (t.equals("Original", ignoreCase = true) || t.equals("original", ignoreCase = true)) {
            return "original"
        }
        if (t.startsWith("Fill ", ignoreCase = true)) {
            return "crop:" + t.substringAfter(' ').replace(" ", "")
        }
        if (t.startsWith("Fit ", ignoreCase = true)) {
            return "pad:" + t.substringAfter(' ').replace(" ", "")
        }
        if (t.startsWith("crop ") || t.startsWith("pad ")) return t.replace(" ", ":")
        return t.replace(" ", ":")
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
        selectValue(aspectRatio, "Original")
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
        aspectRatio.setSelection(p.getInt(KEY_CUSTOM_ASPECT, aspectRatio.selectedItemPosition))
        suppressPersist = false
        persistCurrent(custom = false)
        applyExportUi(exportSwitch.isChecked)
        status.text = "Restored your last settings"
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
        val url = lastFailUrl ?: urlInput.text.toString().trim()
        if (url.isEmpty()) return
        val i = Intent(this, OpenPageActivity::class.java)
        i.putExtra(OpenPageActivity.EXTRA_URL, url)
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
        private const val KEY_ASPECT = "last_aspect"
        private const val KEY_TRIM_START = "trim_start_s"
        private const val KEY_TRIM_END = "trim_end_s"
        private const val KEY_CUSTOM_QUALITY = "custom_quality"
        private const val KEY_CUSTOM_EXPORT_ON = "custom_export_on"
        private const val KEY_CUSTOM_ASPECT = "custom_aspect"
        private const val KEY_BATTERY_ASKED = "battery_opt_asked"
        private const val REQ_POST_NOTIF = 2
        private const val REQ_OPEN_PAGE = 3
    }
}

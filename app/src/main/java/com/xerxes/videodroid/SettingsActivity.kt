package com.xerxes.videodroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xerxes.videodroid.ui.Insets

/**
 * Stage 1: everything that is a *setting* lives here now, instead of being buried in a
 * "More" list on the Download screen. Reached from the gear in the Download header.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SecureScreen.apply(window)
        Insets.applyTo(findViewById(R.id.settingsRoot))

        findViewById<View>(R.id.settingsBack).setOnClickListener { finish() }

        setupParallelSpinner()
        refreshBatteryButton()

        findViewById<Button>(R.id.batteryButton).setOnClickListener { toggleBatteryOptimisation() }
        findViewById<Button>(R.id.loginXButton).setOnClickListener {
            startActivity(Intent(this, XLoginActivity::class.java))
        }
        findViewById<Button>(R.id.clearLoginButton).setOnClickListener {
            XCookies.clear(this)
            toast("X login cleared")
        }
        findViewById<Button>(R.id.localButton).setOnClickListener {
            // The picker + import flow lives in MainActivity; hand off rather than duplicate it.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PICK_LOCAL, true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            finish()
        }
        findViewById<Button>(R.id.myLastButton).setOnClickListener { copyLastLink() }
        findViewById<Button>(R.id.recommendedButton).setOnClickListener { applyRecommended() }
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            UpdateChecker.check(this, installedVersion(), userInitiated = true)
        }
        findViewById<Button>(R.id.changelogButton).setOnClickListener { showChangelog() }
        findViewById<Button>(R.id.copyLogButton).setOnClickListener { copyLog() }

        findViewById<Button>(R.id.engineUpdateButton).setOnClickListener { updateEngine() }
        refreshEngineRow()

        findViewById<Button>(R.id.libraryLockButton).setOnClickListener { toggleLibraryLock() }
        findViewById<Button>(R.id.hideGalleryButton).setOnClickListener { toggleHideGallery() }
        refreshPrivacyRows()

        findViewById<android.widget.TextView>(R.id.settingsVersion).text =
            "VideoDroid ${installedVersion()}"
    }

    // ---- downloads -----------------------------------------------------------------

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

    private fun refreshBatteryButton() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val btn = findViewById<Button>(R.id.batteryButton) ?: return
        btn.text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) "Battery build: unrestricted ✓"
            else "Battery: restricted — tap to fix"
    }

    private fun toggleBatteryOptimisation() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
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

    // ---- sources -------------------------------------------------------------------

    private fun copyLastLink() {
        val url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_JOB_URL, "") ?: ""
        if (url.isEmpty()) {
            toast("No last link yet")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("videodroid-link", url))
        toast("Last link copied")
    }

    /** The same values MainActivity's Recommended action writes. */
    private fun applyRecommended() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, "1080p")
            .putBoolean(KEY_EXPORT_ON, false)
            .putString(KEY_ASPECT_MODE, "Original")
            .putString(KEY_ASPECT_RATIO, "Portrait 9:16")
            .putInt(KEY_TRIM_START, 1)
            .putInt(KEY_TRIM_END, 1)
            .apply()
        toast("Recommended settings applied — open Download")
    }

    // ---- download engine (stage 5) ---------------------------------------------------

    private fun refreshEngineRow() {
        val btn = findViewById<Button>(R.id.engineUpdateButton) ?: return
        val v = EngineUpdater.currentVersion(this)
        val checked = EngineUpdater.lastChecked(this)
        btn.text = buildString {
            append("Download engine: ")
            append(if (v == "bundled") "bundled" else v)
            if (checked.isNotBlank()) append(" (checked $checked)")
        }
    }

    private fun updateEngine() {
        val btn = findViewById<Button>(R.id.engineUpdateButton) ?: return
        if (engineBusy) return
        engineBusy = true
        btn.isEnabled = false
        btn.text = "Download engine: checking…"
        lifecycleScope.launch {
            val outcome = EngineUpdater.update(this@SettingsActivity)
            engineBusy = false
            btn.isEnabled = true
            toast(outcome.getOrNull() ?: ("Update failed: " + (outcome.exceptionOrNull()?.message ?: "unknown")))
            refreshEngineRow()
        }
    }

    private var engineBusy = false

    // ---- privacy (stage 4) ----------------------------------------------------------

    private fun refreshPrivacyRows() {
        val lock = findViewById<Button>(R.id.libraryLockButton) ?: return
        lock.text = if (LibraryLock.enabled(this)) {
            if (LibraryLock.canAuthenticate(this)) "Library lock: on (fingerprint / PIN)"
            else "Library lock: on, but no phone PIN set ⚠"
        } else {
            "Library lock: off — tap to lock"
        }
        val hide = findViewById<Button>(R.id.hideGalleryButton) ?: return
        hide.text = if (GalleryHider.isHidden(this)) {
            "Hide from Gallery: on"
        } else {
            "Hide from Gallery: off"
        }
    }

    private fun toggleLibraryLock() {
        if (LibraryLock.enabled(this)) {
            LibraryLock.setEnabled(this, false)
            toast("Library lock off")
            refreshPrivacyRows()
            return
        }
        if (!LibraryLock.canAuthenticate(this)) {
            toast("Set a phone PIN, pattern or fingerprint first")
            runCatching {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        LibraryLock.setEnabled(this, true)
        toast("Library will ask for fingerprint / PIN")
        refreshPrivacyRows()
    }

    private fun toggleHideGallery() {
        val hide = !GalleryHider.isHidden(this)
        val error = GalleryHider.setHidden(this, hide)
        if (error != null) {
            toast("Could not change the marker: $error")
        } else {
            toast(
                if (hide) "Marker written — gallery apps ignore VideoDroid/"
                else "Marker removed",
            )
        }
        refreshPrivacyRows()
    }

    // ---- app -----------------------------------------------------------------------

    private fun showChangelog() {
        val text = runCatching {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        }.getOrDefault("No changelog bundled.")
        MaterialAlertDialogBuilder(this)
            .setTitle("Changelog")
            .setMessage(JobDiag.redact(text.take(6000)))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun copyLog() {
        val rows = DownloadQueue.all(this)
        val lines = ArrayList<String>()
        lines.add("VideoDroid ${installedVersion()}")
        lines.add("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        lines.add("")
        lines.add("last statuses:")
        val hist = JobDiag.lastStatuses(30)
        if (hist.isEmpty()) lines.add("(none)") else lines.addAll(hist)
        lines.add("")
        lines.add("ffmpeg rc: ${JobDiag.lastFfmpegRc.ifEmpty { "(none)" }}")
        lines.add("exception: ${JobDiag.lastException.ifEmpty { "(none)" }}")
        if (rows.isNotEmpty()) {
            lines.add("")
            lines.add("queue rows (${rows.size}):")
            for (j in rows.take(25)) {
                val what = MediaIdentity.canonicalUrl(j.url) ?: j.localPath.takeLast(60)
                lines.add(
                    "- $what | failed=${j.failed} paused=${j.paused} attempts=${j.attempts} " +
                        "progress=${j.progress}% | ${j.failReason.ifEmpty { "-" }}",
                )
            }
        }
        val saved = JobDiag.history(this, 6000)
        if (saved.isNotBlank()) {
            lines.add("")
            lines.add("saved job log:")
            lines.add(saved)
        }
        val blob = JobDiag.redact(lines.joinToString("\n"))
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("videodroid-log", blob))
        toast("Log copied")
    }

    private fun installedVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }
            .getOrDefault("?")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "videodroid"
        const val KEY_QUALITY = "last_quality"
        const val KEY_EXPORT_ON = "export_on"
        const val KEY_ASPECT_MODE = "last_aspect_mode"
        const val KEY_ASPECT_RATIO = "last_aspect_ratio"
        const val KEY_TRIM_START = "trim_start_s"
        const val KEY_TRIM_END = "trim_end_s"
        const val KEY_LAST_JOB_URL = "last_job_url"
    }
}

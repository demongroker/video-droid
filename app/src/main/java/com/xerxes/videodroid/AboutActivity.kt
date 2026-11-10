package com.xerxes.videodroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xerxes.videodroid.ui.Insets

/**
 * About: identity, version, updates, changelog, licences, and the crash log.
 *
 * The version line is also the hidden test-crash trigger in debug builds (seven taps), which is
 * where that trick belongs now that there is a real About screen.
 */
class AboutActivity : AppCompatActivity() {

    private var versionTaps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureScreen.apply(window)
        setContentView(R.layout.activity_about)
        Insets.applyTo(findViewById(R.id.aboutRoot))

        findViewById<android.view.View>(R.id.aboutBack).setOnClickListener { finish() }

        val version = installedVersion()
        val versionLabel = findViewById<TextView>(R.id.aboutVersion)
        versionLabel.text = "Version $version"
        wireTapToCrash(versionLabel)

        findViewById<Button>(R.id.aboutCheckUpdate).setOnClickListener {
            Haptics.tick(this)
            UpdateChecker.check(this, version, userInitiated = true)
        }
        findViewById<Button>(R.id.aboutChangelog).setOnClickListener { showChangelog() }
        findViewById<Button>(R.id.aboutCrashLogs).setOnClickListener {
            runCatching { startActivity(Intent(this, CrashLogActivity::class.java)) }
        }
        findViewById<Button>(R.id.aboutGitHub).setOnClickListener {
            openUrl("https://github.com/demongroker/video-droid")
        }
        findViewById<Button>(R.id.aboutLicenses).setOnClickListener { showLicenses() }

        // The hint only makes sense where the trigger exists.
        if (!BuildConfig.DEBUG) {
            findViewById<TextView>(R.id.aboutTapHint).visibility = android.view.View.GONE
        }
    }

    private fun installedVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }
            .getOrDefault("?")

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("No app can open that link") }
    }

    private fun showChangelog() {
        val text = runCatching {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        }.getOrDefault("No changelog bundled.")
        MaterialAlertDialogBuilder(this)
            .setTitle("Changelog")
            .setMessage(JobDiag.redact(text.take(6000)))
            .setPositiveButton("Close", null)
            .show()
            .also { SecureScreen.apply(it) }
    }

    private fun showLicenses() {
        val body = """
            VideoDroid is built on open-source work:

            • AndroidX / Material Components — Apache License 2.0
            • Kotlin & Kotlin Coroutines — Apache License 2.0
            • Chaquopy — MIT License (with the Android plugin under its own terms)
            • yt-dlp — The Unlicense
            • ffmpeg-kit / FFmpeg — LGPL 2.1+ (FFmpeg), ffmpeg-kit under LGPL 3.0
            • AndroidX Media3 — Apache License 2.0
            • androidx.biometric — Apache License 2.0

            Full licence texts ship with each project. Nothing in VideoDroid phones home: there is
            no analytics, no crash upload, and no account.
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle("Licenses")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .show()
            .also { SecureScreen.apply(it) }
    }

    /**
     * Debug builds only: seven taps force a crash so the log writer and viewer can be checked.
     * The whole body sits behind BuildConfig.DEBUG, which R8 folds to false in a release build.
     */
    private fun wireTapToCrash(label: TextView) {
        if (!BuildConfig.DEBUG) return
        label.setOnClickListener {
            versionTaps++
            val left = 7 - versionTaps
            if (left in 1..4) toast("$left more tap${if (left == 1) "" else "s"}…")
            if (versionTaps >= 7) {
                versionTaps = 0
                Haptics.thud(this)
                toast("Crashing on purpose (debug build)")
                Handler(Looper.getMainLooper()).postDelayed({
                    throw RuntimeException("VideoDroid test crash — triggered from About")
                }, 300)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context) = Intent(context, AboutActivity::class.java)
    }
}

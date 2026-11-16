package com.xerxes.videodroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.xerxes.videodroid.ui.JobSettings

/**
 * One-tap share target: takes a link from the share sheet, queues it with the settings the user
 * last chose in the app, and gets out of the way.
 *
 * The share sheet shows this as "VideoDroid" and MainActivity's alias as
 * "VideoDroid (choose options)" — the same link, two intents: fire and forget, or open the screen and
 * decide there. Translucent, no layout, no recents entry, its own task: the user never sees a window
 * flash between the share sheet and the toast.
 */
class QuickShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = sharedUrl(intent)
        if (url.isNullOrBlank()) {
            toast("No link found")
            finish()
            return
        }
        DownloadService.start(this, url, savedDefaults().toFormatOptions())
        toast("Sending to VideoDroid…")
        finish()
    }

    /** EXTRA_TEXT (or EXTRA_STREAM's text) — trimmed, first URL-ish token wins. */
    private fun sharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        val raw = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: text.split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
    }

    /** The same snapshot MainActivity's launchJob() builds, read straight from prefs. */
    private fun savedDefaults(): JobSettings {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val quality = prefs.getString(KEY_QUALITY, "1080p") ?: "1080p"
        val exportOn = prefs.getBoolean(KEY_EXPORT_ON, true)
        val mode = prefs.getString(KEY_ASPECT_MODE, "Original") ?: "Original"
        val ratio = prefs.getString(KEY_ASPECT_RATIO, "Portrait 9:16") ?: "Portrait 9:16"
        val aspect = if (exportOn) aspectToken(mode, ratio) else "original"
        return JobSettings(
            quality = quality,
            exportOn = exportOn,
            aspect = aspect,
            trimOn = false,
            trimStart = readTrim(prefs, KEY_TRIM_START),
            trimEnd = readTrim(prefs, KEY_TRIM_END),
            audioOnly = prefs.getBoolean(KEY_AUDIO_ONLY, false),
        )
    }

    private fun readTrim(prefs: android.content.SharedPreferences, key: String, fallback: Int = 0): Int =
        prefs.getInt(key, fallback)

    /** Mode + grouped ratio label → FormatWorker token (original / crop:W:H / pad:W:H). */
    private fun aspectToken(mode: String, ratioLabel: String): String {
        val m = mode.trim()
        if (m.equals("Original", ignoreCase = true)) return "original"
        val spec = ratioLabel.trim().substringAfterLast(' ').replace(" ", "")
        if (m.equals("Fill", ignoreCase = true)) return "crop:$spec"
        if (m.equals("Fit", ignoreCase = true)) return "pad:$spec"
        return "original"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "videodroid"
        const val KEY_QUALITY = "last_quality"
        const val KEY_EXPORT_ON = "export_on"
        const val KEY_ASPECT_MODE = "last_aspect_mode"
        const val KEY_ASPECT_RATIO = "last_aspect_ratio"
        const val KEY_TRIM_START = "trim_start_s"
        const val KEY_TRIM_END = "trim_end_s"
        const val KEY_AUDIO_ONLY = "audio_only"
    }
}

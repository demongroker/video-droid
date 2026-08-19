package com.xerxes.videodroid

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * Non-blocking update checker. No GitHub token. Fail closed.
 *
 * Fetches BuildConfig.UPDATER_URL (GitHub releases/latest), parses tag_name and the first
 * .apk asset browser_download_url, compares to versionName. Download → cache + progress,
 * then install via FileProvider / REQUEST_INSTALL_PACKAGES. Never opens a browser.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    private data class ReleaseInfo(val tag: String, val apkUrl: String)

    fun check(activity: Activity, installedVersion: String) {
        val info = fetchLatest() ?: return
        val latestVersion = info.tag.trim().removePrefix("v").removePrefix("V")
        if (latestVersion.isEmpty()) return
        if (!isNewer(latestVersion, installedVersion)) return
        showDialog(activity, latestVersion, info.apkUrl)
    }

    private fun fetchLatest(): ReleaseInfo? {
        return try {
            val conn = URL(BuildConfig.UPDATER_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "VideoDroid")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) {
                Log.w(TAG, "Update check: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { return null }
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                    apkUrl = url
                    break
                }
            }
            val url = apkUrl ?: return null
            ReleaseInfo(tag, url)
        } catch (e: Throwable) {
            Log.w(TAG, "Update check failed", e)
            null
        }
    }

    private fun showDialog(activity: Activity, version: String, apkUrl: String) {
        val ref = WeakReference(activity)
        activity.runOnUiThread {
            val act = ref.get() ?: return@runOnUiThread
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Update available")
                .setMessage(
                    "Version $version is available. You have ${BuildConfig.VERSION_NAME}.\n" +
                        "Download and install the APK in-app?"
                )
                .setPositiveButton("Download") { _, _ ->
                    startDownload(act, version, apkUrl)
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun startDownload(activity: Activity, version: String, apkUrl: String) {
        val progress = ProgressDialog(activity).apply {
            setTitle("Downloading $version")
            setMessage("Starting…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        val ref = WeakReference(activity)
        Thread {
            val file = downloadApk(activity, apkUrl) { pct, msg ->
                val a = ref.get() ?: return@downloadApk
                a.runOnUiThread {
                    if (!progress.isShowing) return@runOnUiThread
                    if (pct in 0..100) progress.progress = pct
                    progress.setMessage(msg)
                }
            }
            val act = ref.get()
            act?.runOnUiThread {
                try { progress.dismiss() } catch (_: Throwable) { }
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (file == null) {
                    Toast.makeText(act, "Update download failed", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                promptInstall(act, file)
            }
        }.start()
    }

    private fun downloadApk(
        activity: Activity,
        apkUrl: String,
        onProgress: (Int, String) -> Unit,
    ): File? {
        return try {
            val dest = File(activity.cacheDir, "VideoDroid-update.apk")
            if (dest.exists()) dest.delete()
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "VideoDroid")
            conn.setRequestProperty("Accept", "application/octet-stream")
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "APK download HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            val msg = if (pct >= 0) "$pct%" else "${read / 1024} KB"
                            onProgress(if (pct >= 0) pct else 0, msg)
                        }
                    }
                }
            }
            conn.disconnect()
            if (!dest.exists() || dest.length() < 1024) {
                dest.delete()
                return null
            }
            dest
        } catch (e: Throwable) {
            Log.w(TAG, "APK download failed", e)
            null
        }
    }

    private fun promptInstall(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Allow install")
                .setMessage("Enable “Install unknown apps” for VideoDroid, then tap Install again.")
                .setPositiveButton("Settings") { _, _ ->
                    try {
                        val uri = Uri.parse("package:${activity.packageName}")
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                        )
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not open unknown-sources settings", e)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Install prompt failed", e)
            Toast.makeText(activity, "Could not start installer", Toast.LENGTH_LONG).show()
        }
    }

    /** Compares dotted numeric version strings, e.g. "1.10" > "1.9". */
    private fun isNewer(newer: String, older: String): Boolean {
        val a = newer.split(".").mapNotNull { it.toIntOrNull() }
        val b = older.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

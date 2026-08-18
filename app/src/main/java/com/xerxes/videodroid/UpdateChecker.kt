package com.xerxes.videodroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * Non-blocking update checker.
 *
 * On app launch MainActivity runs this in a background thread. It fetches the latest release
 * tag from BuildConfig.UPDATER_URL, compares it to the installed versionName, and only if a
 * newer version exists shows an AlertDialog offering to open the release page to download the
 * new APK. Any failure (no network, 404, non-JSON, etc.) is a silent no-op.
 *
 * NOTE ON THE PRIVATE REPO: the repo demongroker/video-droid is PRIVATE, so the default
 * GitHub "latest release" endpoint (https://api.github.com/repos/demongroker/video-droid/
 * releases/latest) returns 404 for unauthenticated clients. No GitHub token is embedded in
 * the app for security. For auto-update to actually work, one of these must be done:
 *   1. Make the repo (or at least the releases) public, or
 *   2. Host a plain text file containing just the version/tag (and optionally a download URL)
 *      somewhere public and point UPDATER_URL at it (the code only needs a JSON object with a
 *      "tag_name" string field, so a tiny JSON like {"tag_name":"1.2"} is sufficient).
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    private data class ReleaseInfo(val tag: String, val htmlUrl: String?)

    fun check(activity: Activity, installedVersion: String) {
        val info = fetchLatest() ?: return // silent no-op on failure
        val latestVersion = info.tag.trim().removePrefix("v").removePrefix("V")
        if (latestVersion.isEmpty()) return
        if (!isNewer(latestVersion, installedVersion)) return
        showDialog(activity, latestVersion, info.htmlUrl)
    }

    private fun fetchLatest(): ReleaseInfo? {
        return try {
            val conn = URL(BuildConfig.UPDATER_URL).openConnection() as HttpURLConnection
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
            val html = json.optString("html_url").ifBlank { null }
            ReleaseInfo(tag, html)
        } catch (e: Throwable) {
            Log.w(TAG, "Update check failed", e)
            null
        }
    }

    private fun showDialog(activity: Activity, version: String, htmlUrl: String?) {
        val url = htmlUrl ?: return // skip the dialog if no download URL (never open the raw JSON/API endpoint)
        // Keep only a weak reference so the background fetch can't pin a destroyed activity.
        val ref = WeakReference(activity)
        activity.runOnUiThread {
            val act = ref.get() ?: return@runOnUiThread
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Update available")
                .setMessage(
                    "Version $version is available. You have ${BuildConfig.VERSION_NAME}.\n" +
                        "Open the release page to download the new APK?"
                )
                .setPositiveButton("Download") { _, _ ->
                    try {
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not open update URL", e)
                    }
                }
                .setNegativeButton("Later", null)
                .show()
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

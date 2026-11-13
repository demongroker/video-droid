package com.xerxes.videodroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
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
 * GitHub releases/latest only — the old plain-HTTP Tailscale fallback was
 * removed (it pointed at a private LAN address and could be spoofed).
 * User-initiated Check update surfaces HTTP errors.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    /** One background thread: checks never overlap and never run on the UI thread. */
    private val executor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "update-check").apply { isDaemon = true }
        }

    private data class ReleaseInfo(val tag: String, val apkUrl: String)
    private data class FetchOutcome(val info: ReleaseInfo?, val error: String?)

    /**
     * Check for an update **without touching the caller's thread**.
     *
     * This used to call fetchLatest() inline, which is a blocking HTTP request, so every launch
     * and every "Check for updates" tap threw NetworkOnMainThreadException and the toast said so
     * ("GitHub: NetworkOnMainThreadException"). The fetch now runs on a single background thread
     * and only the result is posted back to the UI.
     */
    fun check(activity: Activity, installedVersion: String, userInitiated: Boolean = false) {
        val app = activity.applicationContext
        executor.execute {
            val outcome = runCatching { fetchLatest() }.getOrElse { failure ->
                // Raw exception stays in the local non-fatal log; the UI gets plain English.
                CrashLog.writeNonFatal(app, "update check", failure)
                FetchOutcome(null, "Couldn't check for updates — try again")
            }
            evaluate(activity, installedVersion, userInitiated, outcome)
        }
    }

    /** Runs on the background thread; everything that touches the UI goes through report(). */
    private fun evaluate(
        activity: Activity,
        installedVersion: String,
        userInitiated: Boolean,
        outcome: FetchOutcome,
    ) {
        val info = outcome.info
        val app = activity.applicationContext
        if (info == null) {
            if (userInitiated) {
                val raw = outcome.error.orEmpty()
                report(
                    activity,
                    if (raw.isBlank()) "Couldn't check for updates — try again"
                    else ErrorText.human(raw).ifBlank { "Couldn't check for updates — try again" },
                )
            }
            return
        }
        val latestVersion = info.tag.trim().removePrefix("v").removePrefix("V")
        if (latestVersion.isEmpty()) {
            if (userInitiated) report(activity, "Update check: empty tag_name")
            return
        }

        // One decision point (UpdatePrompt) for every case: a download already in
        // flight, a snooze, no network, Wi-Fi only, a failed attempt, or a real
        // prompt. The old code prompted unconditionally and restarted the
        // download on every tap.
        val decision = UpdatePrompt.decide(
            UpdatePromptInput(
                latestVersion = latestVersion,
                installedVersion = installedVersion,
                nowMillis = System.currentTimeMillis(),
                snoozeUntilMillis = UpdateState.snoozeUntil(app),
                jobVersion = UpdateState.jobVersion(app),
                jobState = UpdateState.jobState(app),
                jobPercent = UpdateState.jobPercent(app),
                jobError = UpdateState.jobError(app),
                online = UpdateState.isOnline(app),
                unmetered = UpdateState.isUnmetered(app),
                wifiOnly = UpdateState.wifiOnly(app),
                userInitiated = userInitiated,
            )
        )

        when (decision.action) {
            UpdateAction.NONE -> if (decision.message.isNotBlank()) report(activity, decision.message)
            UpdateAction.SHOW_DOWNLOADING,
            UpdateAction.SHOW_READY,
            UpdateAction.SHOW_OFFLINE,
            UpdateAction.WAIT_FOR_WIFI -> report(activity, decision.message)
            UpdateAction.SHOW_FAILED,
            UpdateAction.PROMPT -> promptForDownload(activity, latestVersion, info.apkUrl, decision)
        }
    }

    private fun report(activity: Activity, msg: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (activity is MainActivity) {
                activity.showUpdateStatus(msg)
            } else {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchLatest(): FetchOutcome = fetchGithub()

    private fun fetchGithub(): FetchOutcome = fetchJson(BuildConfig.UPDATER_URL)

    private fun fetchJson(url: String): FetchOutcome {
        val label = "GitHub"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "VideoDroid")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "$label update check: HTTP $code")
                conn.disconnect()
                return FetchOutcome(null, "$label HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return FetchOutcome(null, "$label: missing tag_name")
            // HTTPS only — no plain-HTTP update path is allowed.
            val apkUrl = firstGithubApk(json)
            if (apkUrl.isNullOrBlank() || !apkUrl.startsWith("https://")) {
                return FetchOutcome(null, "$label: no apk url")
            }
            FetchOutcome(ReleaseInfo(tag, apkUrl), null)
        } catch (e: Throwable) {
            Log.w(TAG, "$label update check failed", e)
            FetchOutcome(null, "$label: ${e.javaClass.simpleName}")
        }
    }

    private fun firstGithubApk(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                return url
            }
        }
        return null
    }

    /**
     * Shows the update prompt. Called only when [UpdatePrompt] says a prompt is
     * appropriate — never while a download is running, snoozed, offline or
     * waiting for Wi-Fi (those are reported as status text instead, so the main
     * UI is never covered by a dialog the user cannot act on).
     */
    private fun promptForDownload(
        activity: Activity,
        version: String,
        apkUrl: String,
        decision: UpdateDecision,
    ) {
        val ref = WeakReference(activity)
        activity.runOnUiThread {
            val act = ref.get() ?: return@runOnUiThread
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            val retry = decision.action == UpdateAction.SHOW_FAILED
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(if (retry) "Update failed" else "Update available")
                .setMessage("${decision.message}\n\nDownload and install the APK in-app?")
                .setPositiveButton(if (retry) "Retry" else "Download") { _, _ ->
                    startDownload(act, version, apkUrl)
                }
                .setNeutralButton("Wi-Fi only") { _, _ ->
                    // Toggle without leaving the dialog flow: default is ON, and at
                    // sea mobile data is expensive.
                    val now = !UpdateState.wifiOnly(act)
                    UpdateState.setWifiOnly(act, now)
                    Toast.makeText(
                        act,
                        if (now) "Updates will download on Wi-Fi only" else "Updates may use mobile data",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .setNegativeButton("Later") { _, _ ->
                    UpdateState.snooze(act)
                    Toast.makeText(act, "Update reminder snoozed for 24h", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun startDownload(activity: Activity, version: String, apkUrl: String) {
        val app = activity.applicationContext
        // Belt and braces: the service refuses a second start, but asking here
        // means the user gets "downloading (x%)" instead of a silent no-op.
        if (UpdateState.isDownloadActive(app)) {
            Toast.makeText(
                activity,
                UpdatePrompt.downloadingMessage(UpdateState.jobPercent(app)),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (!UpdateState.isOnline(app)) {
            Toast.makeText(activity, "No internet — update will retry when you're back online", Toast.LENGTH_LONG)
                .show()
            return
        }
        if (UpdateState.wifiOnly(app) && !UpdateState.isUnmetered(app)) {
            Toast.makeText(
                activity,
                "Wi-Fi only is on — update $version will download when you're on Wi-Fi",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        UpdateState.clearSnooze(app)
        val started = UpdateService.start(app, version, apkUrl)
        Toast.makeText(
            activity,
            if (started) {
                "Downloading $version in the background — progress is in the status bar"
            } else {
                UpdatePrompt.downloadingMessage(UpdateState.jobPercent(app))
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Fail closed: downloaded APK must share a current signing cert with the
     * installed package (PackageManager signatures / SigningInfo). No skip.
     */
    fun verifyApkSignature(context: Context, apk: File): Boolean {
        return try {
            val pm = context.packageManager
            val archiveInfo = archivePackageInfo(pm, apk) ?: return false
            if (archiveInfo.packageName != context.packageName) {
                Log.w(TAG, "APK package ${archiveInfo.packageName} != ${context.packageName}")
                return false
            }
            val installed = currentSignerBytes(installedPackageInfo(pm, context.packageName))
            val archive = currentSignerBytes(archiveInfo)
            if (installed.isEmpty() || archive.isEmpty()) {
                Log.w(TAG, "Signing certs missing installed=${installed.size} apk=${archive.size}")
                return false
            }
            archive.any { incoming -> installed.any { it.contentEquals(incoming) } }
        } catch (e: Throwable) {
            Log.w(TAG, "APK signing check failed", e)
            false
        }
    }

    private fun installedPackageInfo(pm: PackageManager, packageName: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun archivePackageInfo(pm: PackageManager, apk: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }

    private fun currentSignerBytes(info: PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptyList()
            si.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { it.toByteArray() }.filter { it.isNotEmpty() }
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
    internal fun isNewer(newer: String, older: String): Boolean {
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

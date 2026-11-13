package com.xerxes.videodroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background APK update: downloads in a foreground service so the transfer
 * survives leaving the app, shows progress in the status bar, and resumes a
 * partial file (see [UpdateDownloader]) instead of starting over.
 *
 * Nothing is installed automatically: when the download verifies (size +
 * signing certificate) the notification opens the system installer.
 */
class UpdateService : Service() {

    companion object {
        const val TAG = "UpdateService"
        const val CHANNEL_ID = "updates"
        const val NOTIF_ID = 4201
        const val ACTION_CANCEL = "com.xerxes.videodroid.UPDATE_CANCEL"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"

        /**
         * Single flight. Two Starts used to mean two threads appending to the
         * same `.part` file through RandomAccessFile, which corrupts the
         * download and (on the second foreground start) took the process down:
         * that is the "tap Download twice and the app closes" crash.
         */
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile
        private var runningVersion: String? = null

        fun isDownloadRunning(): Boolean = running.get()

        fun runningVersion(): String? = runningVersion

        /**
         * Starts the download unless one is already in flight.
         * @return false when a download was already running (caller should not
         *         show the prompt again).
         */
        fun start(context: Context, version: String, apkUrl: String): Boolean {
            if (!running.compareAndSet(false, true)) {
                Log.i(TAG, "start ignored: a download is already running ($runningVersion)")
                return false
            }
            runningVersion = version
            UpdateState.beginDownload(context, version)
            val i = Intent(context, UpdateService::class.java).apply {
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_URL, apkUrl)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                true
            } catch (e: Throwable) {
                // Android 12+ refuses a foreground start from the background.
                running.set(false)
                runningVersion = null
                UpdateState.markFailed(context, version, "could not start (${e.javaClass.simpleName})")
                Log.w(TAG, "foreground start refused", e)
                false
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, UpdateService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }

    private val cancelled = AtomicBoolean(false)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background VideoDroid update downloads" }
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            return START_NOT_STICKY
        }
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "update"
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            running.set(false)
            runningVersion = null
            stopSelf(); return START_NOT_STICKY
        }
        // A recreated service (process death) must re-acquire the flag.
        running.compareAndSet(false, true)
        runningVersion = version
        startForeground(NOTIF_ID, progressNotification(version, 0, -1, "Starting…"))
        UpdateState.beginDownload(this, version)

        Thread {
            try {
                val dest = File(cacheDir, "VideoDroid-update.apk")
                val etag = File(cacheDir, "VideoDroid-update.apk.etag")
                    .takeIf { it.exists() }?.readText()?.trim()

                val result = UpdateDownloader().download(
                    url = url,
                    dest = dest,
                    etag = etag,
                    onProgress = { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        if (pct >= 0) UpdateState.progress(this@UpdateService, pct)
                        nm.notify(NOTIF_ID, progressNotification(version, pct, done, null))
                    },
                    isCancelled = { cancelled.get() },
                )

                if (cancelled.get()) {
                    UpdateState.reset(this@UpdateService)
                    nm.cancel(NOTIF_ID)
                    return@Thread
                }
                if (result.file == null) {
                    val reason = result.error ?: "unknown error"
                    UpdateState.markFailed(this@UpdateService, version, reason)
                    nm.notify(NOTIF_ID, failureNotification(version, reason))
                    return@Thread
                }
                if (!UpdateChecker.verifyApkSignature(this@UpdateService, result.file)) {
                    result.file.delete()
                    UpdateState.markFailed(this@UpdateService, version, "signature mismatch — refused")
                    nm.notify(NOTIF_ID, failureNotification(version, "signature mismatch — refused"))
                    return@Thread
                }
                // Integrity before anything is offered to the user: the release
                // publishes `<asset>.apk.sha256`; a mismatch is a hard failure and
                // a missing checksum is refused rather than trusted.
                val expected = fetchPublishedSha256(url)
                if (expected == null) {
                    result.file.delete()
                    UpdateState.markFailed(this@UpdateService, version, "no published checksum")
                    nm.notify(NOTIF_ID, failureNotification(version, "no published checksum — refused"))
                    return@Thread
                }
                val actual = FileDigest.sha256(result.file)
                if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                    result.file.delete()
                    UpdateState.markFailed(this@UpdateService, version, "checksum mismatch")
                    nm.notify(NOTIF_ID, failureNotification(version, "checksum mismatch — refused"))
                    return@Thread
                }
                UpdateState.markVerified(this@UpdateService, version)
                nm.notify(NOTIF_ID, readyNotification(version, result.file))
            } catch (e: Throwable) {
                // Nothing thrown inside this thread may reach the default handler:
                // an uncaught exception there kills the whole app process.
                Log.w(TAG, "update download failed", e)
                UpdateState.markFailed(this@UpdateService, version, e.javaClass.simpleName)
                runCatching { nm.notify(NOTIF_ID, failureNotification(version, e.javaClass.simpleName)) }
            } finally {
                running.set(false)
                runningVersion = null
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    /**
     * Reads the checksum published beside the APK (`<url>.sha256`, first token).
     * Returns null when it cannot be fetched — the caller then refuses the install.
     */
    private fun fetchPublishedSha256(apkUrl: String): String? = try {
        val conn = (java.net.URL("$apkUrl.sha256").openConnection() as java.net.HttpURLConnection)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "VideoDroid")
        val body = if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
        conn.disconnect()
        body?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
    } catch (e: Throwable) {
        Log.w(TAG, "checksum fetch failed", e)
        null
    }

    private fun baseBuilder(version: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VideoDroid $version")
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    private fun progressNotification(
        version: String,
        pct: Int,
        bytes: Long,
        message: String?,
    ): Notification {
        val b = baseBuilder(version)
        if (pct in 0..100) {
            b.setProgress(100, pct, false)
                .setContentText(if (pct > 0) "$pct%" else message ?: "Starting…")
        } else {
            b.setProgress(0, 0, true)
                .setContentText(if (bytes > 0) "${bytes / 1024 / 1024} MB" else message ?: "Starting…")
        }
        b.addAction(0, "Cancel", cancelPendingIntent())
        return b.build()
    }

    private fun readyNotification(version: String, apk: File): Notification {
        val install = installIntent(apk)
        val pi = PendingIntent.getActivity(
            this, 1, install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("VideoDroid $version ready")
            .setContentText("Tap to install")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun failureNotification(version: String, reason: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Update failed")
            .setContentText("$version: $reason")
            .setAutoCancel(true)
            .build()

    private fun cancelPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this, 2,
            Intent(this, UpdateService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun installIntent(apk: File): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

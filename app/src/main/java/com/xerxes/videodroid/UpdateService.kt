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

        fun start(context: Context, version: String, apkUrl: String) {
            val i = Intent(context, UpdateService::class.java).apply {
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_URL, apkUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
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
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, progressNotification(version, 0, -1, "Starting…"))

        Thread {
            val dest = File(cacheDir, "VideoDroid-update.apk")
            val etag = File(cacheDir, "VideoDroid-update.apk.etag")
                .takeIf { it.exists() }?.readText()?.trim()

            val result = UpdateDownloader().download(
                url = url,
                dest = dest,
                etag = etag,
                onProgress = { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                    nm.notify(NOTIF_ID, progressNotification(version, pct, done, null))
                },
                isCancelled = { cancelled.get() },
            )

            if (cancelled.get()) {
                nm.cancel(NOTIF_ID)
                stopSelf(); return@Thread
            }
            if (result.file == null) {
                nm.notify(
                    NOTIF_ID,
                    failureNotification(version, result.error ?: "unknown error"),
                )
                stopSelf(); return@Thread
            }
            if (!UpdateChecker.verifyApkSignature(this, result.file)) {
                result.file.delete()
                nm.notify(NOTIF_ID, failureNotification(version, "signature mismatch — refused"))
                stopSelf(); return@Thread
            }
            nm.notify(NOTIF_ID, readyNotification(version, result.file))
            stopSelf()
        }.start()

        return START_NOT_STICKY
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

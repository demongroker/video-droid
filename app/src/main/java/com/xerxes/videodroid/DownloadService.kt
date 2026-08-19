package com.xerxes.videodroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DownloadService : Service() {

    interface Listener {
        fun onStatus(msg: String)
        fun onFinished(result: FormatResult)
    }

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var lastJobLine: String = "Starting..."

    private val pumpLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                FormatWorker.requestCancel()
                workerThread?.interrupt()
                publish("Cancelling...")
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    val opts = FormatOptions(
                        dlQuality = intent.getStringExtra(EXTRA_DLQ) ?: "720p",
                        exportHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080),
                        aspect = intent.getStringExtra(EXTRA_ASPECT) ?: "original",
                        crf = intent.getIntExtra(EXTRA_QV, 4),
                        trimEnabled = intent.getBooleanExtra(EXTRA_TRIM, true),
                        trimStart = intent.getIntExtra(EXTRA_TSTART, 1),
                        trimEnd = intent.getIntExtra(EXTRA_TEND, 1),
                        exportEnabled = intent.getBooleanExtra(EXTRA_EXPORT, true),
                    )
                    DownloadQueue.enqueue(applicationContext, QueuedJob(url, opts))
                }
                ensurePump()
            }
            else -> {
                // Process death / sticky restart: resume remaining URLs.
                if (DownloadQueue.size(applicationContext) > 0) ensurePump()
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun ensurePump() {
        startInForeground(composeNotif(lastJobLine))
        synchronized(pumpLock) {
            val live = workerThread
            if (live != null && live.isAlive) {
                publish(lastJobLine)
                return
            }
            val job = Thread { pumpLoop() }
            workerThread = job
            job.start()
        }
    }

    private fun pumpLoop() {
        try {
            while (true) {
                val next = DownloadQueue.peek(applicationContext)
                if (next == null) {
                    synchronized(pumpLock) {
                        if (DownloadQueue.size(applicationContext) == 0 &&
                            Thread.currentThread() === workerThread
                        ) {
                            workerThread = null
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return
                        }
                    }
                    continue
                }
                lastJobLine = "Starting..."
                publish(lastJobLine)
                val res = try {
                    FormatWorker.run(applicationContext, next.url, next.opts) { msg ->
                        lastJobLine = msg
                        publish(msg)
                    }
                } catch (t: Throwable) {
                    FormatResult(false, t.message ?: "Failed")
                }
                DownloadQueue.completeCurrent(applicationContext)
                listener?.onFinished(res)
            }
        } catch (t: Throwable) {
            synchronized(pumpLock) {
                if (Thread.currentThread() === workerThread) workerThread = null
            }
            if (DownloadQueue.size(applicationContext) > 0) ensurePump()
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun withJobDetail(jobLine: String): String {
        val opts = DownloadQueue.peek(applicationContext)?.opts ?: return jobLine
        if (jobLine.contains(" · trim ")) return jobLine
        val active = jobLine.contains("Downloading") ||
            jobLine.contains("Converting") ||
            jobLine.contains("Queued") ||
            jobLine.contains("Starting") ||
            jobLine.contains("Trimming")
        if (!active) return jobLine
        return "$jobLine\n${opts.statusDetail()}"
    }

    private fun publish(jobLine: String) {
        val detailed = withJobDetail(jobLine)
        val waiting = DownloadQueue.waiting(applicationContext)
        val ui = if (waiting > 0) "Queued ($waiting)\n$detailed" else detailed
        updateNotification(composeNotif(detailed, waiting))
        listener?.onStatus(ui)
    }

    private fun composeNotif(jobLine: String, waiting: Int = DownloadQueue.waiting(applicationContext)): String {
        return if (waiting > 0) "$jobLine · $waiting waiting" else jobLine
    }

    private fun startInForeground(msg: String) {
        ensureChannel()
        val n = buildNotification(msg)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(msg))
    }

    private fun buildNotification(msg: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pct = Regex("""(?:Downloading|Converting(?: \([^)]+\))?|Trimming) (\d+)%""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        val first = msg.lineSequence().firstOrNull() ?: msg
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VideoDroid")
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
        if (pct != null) {
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.xerxes.videodroid.START"
        const val ACTION_CANCEL = "com.xerxes.videodroid.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_DLQ = "dlq"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_ASPECT = "aspect"
        const val EXTRA_TRIM = "trim"
        const val EXTRA_TSTART = "tstart"
        const val EXTRA_TEND = "tend"
        const val EXTRA_EXPORT = "export"
        const val EXTRA_QV = "qv"
        private const val CHANNEL_ID = "vd_dl"
        private const val NOTIF_ID = 14

        @Volatile
        var listener: Listener? = null

        fun start(ctx: Context, url: String, opts: FormatOptions) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DLQ, opts.dlQuality)
                putExtra(EXTRA_HEIGHT, opts.exportHeight)
                putExtra(EXTRA_ASPECT, opts.aspect)
                putExtra(EXTRA_TRIM, opts.trimEnabled)
                putExtra(EXTRA_TSTART, opts.trimStart)
                putExtra(EXTRA_TEND, opts.trimEnd)
                putExtra(EXTRA_EXPORT, opts.exportEnabled)
                putExtra(EXTRA_QV, opts.crf)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            FormatWorker.requestCancel()
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }

        fun lastUriKey() = "last_result_uri"
    }
}

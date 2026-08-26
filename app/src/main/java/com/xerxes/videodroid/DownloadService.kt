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
import androidx.core.app.TaskStackBuilder

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

    // Notification channel setup
    private val NOTIFICATION_CHANNEL_ID = "videodroid_download_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VideoDroid Downloads",
                NotificationImportance.LOW
            ).apply {
                description = "Background download and processing service"
                importance = NotificationImportance.LOW
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                FormatWorker.requestCancel()
                workerThread?.interrupt()
                publish("Cancelling...")
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank() || intent.getStringExtra(EXTRA_LOCAL_PATH)?.isNotBlank() == true) {
                    // Get job ID from intent
                    val jobId = intent.getLongExtra(EXTRA_JOB_ID, 0L)

                    // Update job status to DOWNLOADING in database
                    DownloadQueue.updateJobStatus(applicationContext, jobId, "DOWNLOADING", "", 0)

                    val opts = FormatOptions(
                        dlQuality = intent.getStringExtra(EXTRA_DLQ) ?: "720p",
                        exportHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080),
                        aspect = intent.getStringExtra(EXTRA_ASPECT) ?: "original",
                        crf = intent.getIntExtra(EXTRA_QV, 3),
                        trimEnabled = intent.getBooleanExtra(EXTRA_TRIM, true),
                        trimStart = FormatOptions.clampTrimSeconds(intent.getIntExtra(EXTRA_TSTART, 1)),
                        trimEnd = FormatOptions.clampTrimSeconds(intent.getIntExtra(EXTRA_TEND, 1)),
                        exportEnabled = intent.getBooleanExtra(EXTRA_EXPORT, true),
                    )

                    // Enqueue job
                    DownloadQueue.enqueue(applicationContext, com.xerxes.videodroid.QueuedJob(
                        url = url.orEmpty(),
                        opts = opts,
                        localPath = intent.getStringExtra(EXTRA_LOCAL_PATH)?.orEmpty() ?: "",
                        startedAt = System.currentTimeMillis()
                    ))

                    // Mark as started in database
                    DownloadQueue.updateJobStatus(applicationContext, jobId, "DOWNLOADING", "", 0)

                    ensurePump()
                }
            }
            else -> {
                // Process death / sticky restart: resume remaining URLs from database
                if (DownloadQueue.size(applicationContext) > 0) ensurePump()
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
                    // Get job options from database
                    val opts = next.opts ?: FormatOptions(
                        dlQuality = "720p", exportHeight = 1080,
                        aspect = "original", fps = 30, crf = 3,
                        trimEnabled = true, trimStart = 1, trimEnd = 1,
                        exportEnabled = true
                    )

                    FormatWorker.run(
                        applicationContext,
                        next.url,
                        opts,
                        { msg -> lastJobLine = msg; publish(msg) },
                        next.localPath,
                        next.id
                    )
                } catch (t: Throwable) {
                    // Update job status with error
                    val errorMsg = t.message ?: "Failed"
                    DownloadQueue.updateJobStatus(
                        applicationContext,
                        next.id,
                        "FAILED",
                        errorMsg,
                        0,
                        System.currentTimeMillis()
                    )
                    FormatResult(false, errorMsg)
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

    private fun composeNotif(detailed: String, waiting: Int = 0): Notification {
        val contentTitle = if (waiting > 0) "VideoDroid (Queued $waiting)" else "VideoDroid"
        val contentText = detailed

        val resultIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TASK)
        }
        val stackBuilder = TaskStackBuilder.create(this)
        stackBuilder.addNextIntent(resultIntent)
        val pendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val bigTextStyle = androidx.core.app.BigTextStyle().apply {
            bigText = detailed
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).apply {
            setContentTitle(contentTitle)
            setContentText(contentText)
            setSmallIcon(R.drawable.ic_launcher_foreground) // ensure icon exists
            setContentIntent(pendingIntent)
            setStyle(bigTextStyle)
            setOnlyAlertOnce(true)
        }.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent) {
        // Service is killed when task is removed, but we want it to restart
        // on sticky mode. The pumpLoop will check database on restart.
        ensurePump()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_LOCAL_PATH = "extra_local_path"
        const val EXTRA_DLQ = "extra_dlq"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_ASPECT = "extra_aspect"
        const val EXTRA_QV = "extra_qv"
        const val EXTRA_TRIM = "extra_trim"
        const val EXTRA_TSTART = "extra_tstart"
        const val EXTRA_TEND = "extra_tend"
        const val EXTRA_EXPORT = "extra_export"
        const val EXTRA_JOB_ID = "extra_job_id"
    }
}

package com.xerxes.videodroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped from recents must not kill an in-flight transfer: the service
        // keeps the queue draining and WorkManager re-arms it if the process dies.
        super.onTaskRemoved(rootIntent)
    }

    /** Held while a job runs so the CPU and the radio never doze mid-transfer. */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoDroid:download")?.apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VideoDroid:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        isRunning = false
        stopWatchingNetwork()
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        acquireLocks()
        watchNetwork()
        when (intent?.action) {
            ACTION_CANCEL -> {
                FormatWorker.requestCancel()
                workerThread?.interrupt()
                publish("Cancelling...")
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank() || intent.getStringExtra(EXTRA_LOCAL_PATH)?.isNotBlank() == true) {
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
                    DownloadQueue.enqueue(
                        applicationContext,
                        QueuedJob(
                            url = url.orEmpty(),
                            opts = opts,
                            localPath = intent.getStringExtra(EXTRA_LOCAL_PATH).orEmpty(),
                        ),
                    )
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
                    FormatWorker.run(applicationContext, next.url, next.opts, { msg ->
                        lastJobLine = msg
                        publish(msg)
                    }, next.localPath)
                } catch (t: Throwable) {
                    FormatResult(false, t.message ?: "Failed")
                }
                DownloadQueue.finish(applicationContext, next.id)
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
        persistProgress(jobLine)
        updateNotification(composeNotif(detailed, waiting))
        listener?.onStatus(ui)
    }

    private var lastPersistedPct = -1

    /** Remember how far this item got, so a restart can resume and show it. */
    private fun persistProgress(jobLine: String) {
        val pct = JOB_PCT.find(jobLine)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (pct == lastPersistedPct) return
        lastPersistedPct = pct
        val head = DownloadQueue.peek(applicationContext) ?: return
        val dir = FormatWorker.partialDirFor(applicationContext, head.url, head.localPath)
        DownloadQueue.setProgress(applicationContext, head.id, pct, dir.absolutePath)
    }

    // ---- connectivity ----

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var networkLost = false

    /** React to the radio coming back instead of waiting for a socket timeout. */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkLost = true
                if (isRunning) publish("Waiting for connection… (resumes automatically)")
            }

            override fun onAvailable(network: Network) {
                if (!networkLost) return
                networkLost = false
                if (isRunning) publish("Connection restored — resuming…")
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        netCallback = cb
    }

    private fun stopWatchingNetwork() {
        val cb = netCallback ?: return
        runCatching {
            (getSystemService(ConnectivityManager::class.java))?.unregisterNetworkCallback(cb)
        }
        netCallback = null
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
        private val JOB_PCT =
            Regex("""(?:Downloading|Converting(?: \([^)]+\))?|Trimming) (\d+)%""")

        /** True while a job is in flight; read by QueueResumeWorker. */
        @Volatile
        var isRunning: Boolean = false

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
        const val EXTRA_LOCAL_PATH = "localPath"
        private const val CHANNEL_ID = "vd_dl"
        private const val NOTIF_ID = 14

        @Volatile
        var listener: Listener? = null

        fun start(ctx: Context, url: String, opts: FormatOptions, localPath: String = "") {
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
                putExtra(EXTRA_LOCAL_PATH, localPath)
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

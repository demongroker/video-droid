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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class DownloadService : Service() {

    interface Listener {
        fun onStatus(msg: String)
        fun onFinished(result: FormatResult)
    }

    /** Live worker threads, one per in-flight download (bounded by the user's setting). */
    private val workerThreads = java.util.Collections.synchronizedSet(HashSet<Thread>())

    private val liveStatus = java.util.concurrent.ConcurrentHashMap<String, String>()

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
                publishGlobal("Cancelling...")
            }
            ACTION_PAUSE -> {
                pauseAll(this)
                publishGlobal("Paused — tap Resume in the notification or the queue")
            }
            ACTION_RESUME -> {
                resumeAll(this)
                publishGlobal("Resuming...")
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
                    val added = DownloadQueue.enqueue(
                        applicationContext,
                        QueuedJob(
                            url = url.orEmpty(),
                            opts = opts,
                            localPath = intent.getStringExtra(EXTRA_LOCAL_PATH).orEmpty(),
                        ),
                    )
                    if (added < 0) publishGlobal("Already in queue — that link is waiting. See the Queue tab.")
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

    private fun maxParallel(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_PARALLEL, DEFAULT_PARALLEL)
            .coerceIn(1, 5)

    /** Keep the pool topped up: claim work until the limit, then run it. */
    private fun ensurePump() {
        startInForeground(composeNotif(lastJobLine, summaryLine()))
        synchronized(pumpLock) {
            val limit = maxParallel()
            while (workerThreads.size < limit) {
                val next = DownloadQueue.claimNext(applicationContext) ?: break
                liveStatus[next.id] = "Starting..."
                val t = Thread { runOne(next) }
                t.isDaemon = true
                workerThreads.add(t)
                t.start()
            }
        }
        maybeStopWhenIdle()
    }

    /** One claimed item: download (and export) it, then release the claim. */
    private fun runOne(job: QueuedJob) {
        try {
            publish(job.id, "Starting...")
            val res = try {
                FormatWorker.run(
                    applicationContext, job.url, job.opts,
                    { msg -> publish(job.id, msg) },
                    job.localPath, job.id,
                )
            } catch (t: Throwable) {
                FormatResult(false, t.message ?: "Failed")
            }
            if (res.ok) {
                DownloadQueue.finish(applicationContext, job.id)
            } else if (res.message.equals("Cancelled", ignoreCase = true)) {
                // A user stop is not a failure. Release the claim and leave the row as it is —
                // paused, or already removed by the Queue screen. Recording it as failed made a
                // cancelled download look like a permanently broken link.
                DownloadQueue.releaseClaim(job.id)
            } else {
                DownloadQueue.markFailure(applicationContext, job.id, res.message)
                scheduleRetryPump()
            }
            liveStatus.remove(job.id)
            listener?.onFinished(res)
        } catch (_: Throwable) {
            runCatching { DownloadQueue.finish(applicationContext, job.id) }
        } finally {
            synchronized(pumpLock) {
                workerThreads.remove(Thread.currentThread())
            }
            runCatching { ensurePump() }
        }
    }

    private fun maybeStopWhenIdle() {
        synchronized(pumpLock) {
            if (workerThreads.isNotEmpty()) return
            if (DownloadQueue.size(applicationContext) > 0) return
            DownloadQueue.releaseAllClaims()
            liveStatus.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun summaryLine(): String {
        val running = DownloadQueue.inFlightIds().size
        val waiting = DownloadQueue.waiting(applicationContext)
        val parts = ArrayList<String>()
        if (running > 0) parts.add("Downloading $running")
        if (waiting > 0) parts.add("$waiting queued")
        return if (parts.isEmpty()) lastJobLine else parts.joinToString(" · ")
    }

    private fun withJobDetail(jobLine: String, id: String? = null): String {
        val opts = (if (id != null) DownloadQueue.get(applicationContext, id)?.opts else null)
            ?: DownloadQueue.peek(applicationContext)?.opts ?: return jobLine
        if (jobLine.contains(" · trim ")) return jobLine
        val active = jobLine.contains("Downloading") ||
            jobLine.contains("Converting") ||
            jobLine.contains("Queued") ||
            jobLine.contains("Starting") ||
            jobLine.contains("Trimming")
        if (!active) return jobLine
        return "$jobLine\n${opts.statusDetail()}"
    }

    /** Per-item status update: drives the summary notification and the UI. */
    private fun publish(id: String, jobLine: String) {
        lastJobLine = jobLine
        liveStatus[id] = jobLine
        // Speed / ETA are per-item and come straight from yt-dlp's progress line, so surface
        // them on the queue row instead of forcing the owner to open the log.
        liveDetailMap[id] = parseDetail(jobLine)
        persistProgress(id, jobLine)
        val summary = summaryLine()
        updateNotification(composeNotif(withJobDetail(jobLine, id), summary))
        listener?.onStatus("$summary\n${withJobDetail(jobLine, id)}")
    }

    /** Pull "2.4MiB/s" and "ETA 00:03" out of a yt-dlp progress line. */
    private fun parseDetail(jobLine: String): String {
        val speed = SPEED_RE.find(jobLine)?.groupValues?.get(1)?.trim()
        val eta = ETA_RE.find(jobLine)?.groupValues?.get(1)?.trim()?.let { prettyEta(it) }
        return listOfNotNull(
            speed,
            eta?.let { "${it} left" }
        ).joinToString(" · ")
    }

    /** 00:03:12 → 3m 12s; 01:20 → 1m 20s. Long ETAs read badly in a row subtitle. */
    private fun prettyEta(raw: String): String {
        val parts = raw.split(':').mapNotNull { it.toIntOrNull() }
        val secs = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> return raw
        }
        if (secs <= 0) return raw
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Status that is not tied to one item (start/cancel messages). */
    private fun publishGlobal(jobLine: String) {
        lastJobLine = jobLine
        updateNotification(composeNotif(jobLine, summaryLine()))
        listener?.onStatus(jobLine)
    }

    /** Remember how far each item got, so a restart can resume and show it. */
    private val lastPersistedPct = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun persistProgress(id: String, jobLine: String) {
        val pct = JOB_PCT.find(jobLine)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (lastPersistedPct[id] == pct) return
        lastPersistedPct[id] = pct
        val job = DownloadQueue.get(applicationContext, id) ?: return
        val dir = FormatWorker.partialDirFor(applicationContext, job.url, job.localPath)
        DownloadQueue.setProgress(applicationContext, id, pct, dir.absolutePath)
    }

    // ---- connectivity ----

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var networkLost = false

    // Watchdog state: the callback alone is not reliable enough to un-stick a lost network.
    private val netProbeHandler = Handler(Looper.getMainLooper())
    private var netProbe: Runnable? = null
    private var netLostMinutes = 0

    /**
     * Re-probe the radio while [networkLost] is set.
     *
     * Relying only on the NetworkCallback is the bug that made the queue hang on
     * "Waiting for connection… (resumes automatically)" for hours: if `onAvailable` never
     * arrives — radio flap while the screen is off, doze, or the callback dying with the
     * process — the flag latches true forever and nothing ever re-checks. So poll, and after
     * [NET_LOST_GIVE_UP_MIN] minutes stop promising a resume and say so plainly.
     */
    private fun startNetProbe() {
        if (netProbe != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val r = object : Runnable {
            override fun run() {
                if (!networkLost) {
                    stopNetProbe()
                    return
                }
                if (cm.activeNetwork != null) {
                    networkLost = false
                    netLostMinutes = 0
                    stopNetProbe()
                    if (isRunning) {
                        publishGlobal("Connection restored — resuming…")
                        resume(this@DownloadService)
                    }
                    return
                }
                netLostMinutes++
                if (netLostMinutes >= NET_LOST_GIVE_UP_MIN) {
                    if (isRunning) {
                        publishGlobal("No connection for ${netLostMinutes}m — paused. Tap Resume all.")
                    }
                    stopNetProbe()
                } else {
                    netProbeHandler.postDelayed(this, NET_PROBE_MS)
                }
            }
        }
        netProbe = r
        netProbeHandler.postDelayed(r, NET_PROBE_MS)
    }

    private fun stopNetProbe() {
        netProbe?.let { netProbeHandler.removeCallbacks(it) }
        netProbe = null
        netLostMinutes = 0
    }

    /**
     * A retryable failure left an item waiting out its backoff. Nothing else will wake the
     * pump for it, so schedule one check at the earliest retry time — otherwise the item sits
     * "Waiting" until the user happens to add something else.
     */
    private fun scheduleRetryPump() {
        val at = DownloadQueue.nextRetryAt(applicationContext)
        if (at <= 0L) return
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(1_000L)
        netProbeHandler.postDelayed({
            if (isRunning) ensurePump()
        }, delay)
    }

    /** React to the radio coming back instead of waiting for a socket timeout. */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkLost = true
                if (isRunning) publishGlobal("Waiting for connection… (resumes automatically)")
                startNetProbe()
            }

            override fun onAvailable(network: Network) {
                if (!networkLost) return
                networkLost = false
                stopNetProbe()
                if (isRunning) publishGlobal("Connection restored — resuming…")
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

    private fun composeNotif(jobLine: String, summary: String): String =
        if (summary.isBlank() || summary == jobLine) jobLine else "$summary\n$jobLine"



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
        // Pause/resume from the shade: a stuck or unwanted transfer should not require
        // opening the app, and the label has to reflect what is actually paused right now.
        val pausedNow = runCatching { DownloadQueue.pausedCount(this) }.getOrDefault(0)
        val toggleAction = if (pausedNow > 0) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (pausedNow > 0) "Resume" else "Pause"
        val toggle = PendingIntent.getService(
            this, 2,
            Intent(this, DownloadService::class.java).setAction(toggleAction),
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
            .addAction(0, toggleLabel, toggle)
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
        private const val PREFS = "videodroid"
        const val KEY_PARALLEL = "parallel_downloads"
        const val DEFAULT_PARALLEL = 3

        /** How often to re-check the radio while "waiting for connection". */
        private const val NET_PROBE_MS = 60_000L

        /** `2.4MiB/s`, `850.12KiB/s` — yt-dlp's progress rate. */
        private val SPEED_RE = Regex("""([\d.]+\s*[KMG]i?B/s)""")

        /** `ETA 00:03` / `ETA 01:20:05`. */
        private val ETA_RE = Regex("""ETA\s+(\d+(?::\d+){0,2})""")

        /**
         * Live "speed · ETA" for one queue item, or "" when it is not transferring.
         * Lives in the companion so the queue UI can read it without binding to the service.
         */
        private val liveDetailMap = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun liveDetail(id: String): String = liveDetailMap[id].orEmpty()

        /**
         * After this many probes with no active network, stop claiming the transfer will
         * resume by itself and tell the owner to resume manually instead.
         */
        private const val NET_LOST_GIVE_UP_MIN = 5

        private val JOB_PCT =
            Regex("""(?:Downloading|Converting(?: \([^)]+\))?|Trimming) (\d+)%""")

        /** True while a job is in flight; read by QueueResumeWorker. */
        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "com.xerxes.videodroid.START"
        const val ACTION_CANCEL = "com.xerxes.videodroid.CANCEL"
        const val ACTION_PAUSE = "com.xerxes.videodroid.PAUSE_ALL"
        const val ACTION_RESUME = "com.xerxes.videodroid.RESUME_ALL"
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

        /**
         * Pause one item. The row, progress and .part file are kept, so resuming continues
         * rather than restarting. If this item is the one running, stop it without wiping
         * its partials (FormatWorker.requestPause, not requestCancel).
         */
        fun pauseOne(ctx: Context, id: String) {
            val wasRunning = DownloadQueue.isClaimed(id)
            DownloadQueue.pause(ctx, id)
            // Per-item: only this row's worker stops. The other in-flight downloads keep
            // going — a global flag here used to fail every one of them as "Cancelled".
            if (wasRunning) FormatWorker.requestPause(id)
        }

        fun resumeOne(ctx: Context, id: String) {
            DownloadQueue.resume(ctx, id)
            resume(ctx)
        }

        /** Pause every pending/running item. Failed rows are left visible as failures. */
        fun pauseAll(ctx: Context): Int {
            val ids = DownloadQueue.pauseAll(ctx)
            if (ids.isNotEmpty()) FormatWorker.requestPause()
            return ids.size
        }

        fun resumeAll(ctx: Context): Int {
            val n = DownloadQueue.resumeAll(ctx)
            if (n > 0) resume(ctx)
            return n
        }

        /** Kick the pump without enqueuing anything (used by queue retry). */
        fun resume(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun lastUriKey() = "last_result_uri"
    }
}

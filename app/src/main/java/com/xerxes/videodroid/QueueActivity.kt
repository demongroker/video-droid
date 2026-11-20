package com.xerxes.videodroid

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import com.xerxes.videodroid.ui.Insets
import com.xerxes.videodroid.ui.QueueScreen
import com.xerxes.videodroid.ui.theme.VideoDroidTheme
import java.io.File

/**
 * Queue: what is downloading now, what is waiting, and what failed.
 *
 * The list is Compose ([QueueScreen]); this Activity owns every side effect and the persisted
 * FIFO it reads. Swipe gestures were replaced by explicit Share / Cancel buttons — a mis-swipe
 * can no longer remove a row, and the actions are discoverable.
 *
 * Contracts unchanged: pause keeps the partial file, cancel asks first and stops only that row,
 * retry appears only for retryable errors, and the failure reason stays in the state line.
 */
class QueueActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    // Compose state: the 1 s ticker writes these, the screen recomposes. No re-setContent.
    private val jobs = mutableStateOf<List<QueuedJob>>(emptyList())
    private val pausedCount = mutableIntStateOf(0)
    private val online = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        Insets.applyTo(findViewById(R.id.queueRoot))
        SecureScreen.apply(window)
        NavHelper.wire(this, R.id.tab_queue)

        findViewById<ComposeView>(R.id.queueCompose).setContent {
            VideoDroidTheme {
                QueueScreen(
                    jobs = jobs.value,
                    pausedCount = pausedCount.intValue,
                    online = online.value,
                    isClaimed = { id -> DownloadQueue.isClaimed(id) },
                    liveDetail = { id -> DownloadService.liveDetail(id) },
                    loadThumb = { job, iv -> QueueThumb.load(this, job, iv) },
                    onOpen = { job -> openItem(job) },
                    onRetry = { job -> retry(job) },
                    onCancel = { job -> cancelOne(job) },
                    onShare = { job -> share(job) },
                    onPauseToggle = { job ->
                        if (job.paused) DownloadService.resumeOne(this, job.id)
                        else DownloadService.pauseOne(this, job.id)
                        reload()
                    },
                    onPauseAll = {
                        // Pause all keeps every partial file and progress, so resuming
                        // continues the transfers instead of restarting them.
                        if (DownloadQueue.pausedCount(this) > 0) DownloadService.resumeAll(this)
                        else DownloadService.pauseAll(this)
                        reload()
                    },
                    onRetryFailed = {
                        val n = DownloadQueue.retryAllFailed(this)
                        DownloadService.resume(this)
                        toast("Retrying $n item(s)")
                        reload()
                    },
                    onClearFailed = {
                        val n = DownloadQueue.clearFailed(this)
                        toast("Cleared $n failed item(s)")
                        reload()
                    },
                    onClearAll = { confirmStopAll() },
                    onGoDownload = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                        finish()
                    },
                )
            }
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        NavHelper.sync(this, R.id.tab_queue)
        ticker = object : Runnable {
            override fun run() {
                reload()
                handler.postDelayed(this, 1000L)
            }
        }.also { handler.post(it) }
    }

    override fun onPause() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        super.onPause()
    }

    /** Reads the persisted queue into Compose state. */
    private fun reload() {
        jobs.value = DownloadQueue.all(this)
        pausedCount.intValue = DownloadQueue.pausedCount(this)
        online.value = isOnline()
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun share(job: QueuedJob) {
        val file = job.localPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.isFile) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "video/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share",
                ),
            )
        } else {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, job.url.ifBlank { job.displayName })
                    },
                    "Share link",
                ),
            )
        }
    }

    private fun retry(job: QueuedJob) {
        DownloadQueue.retry(this, job.id)
        DownloadService.resume(this)
        reload()
    }

    private fun openItem(job: QueuedJob) {
        val path = job.localPath
        if (path.isBlank()) return
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
    }

    private fun cancelOne(job: QueuedJob) {
        val running = DownloadQueue.isClaimed(job.id)
        AlertDialog.Builder(this)
            .setTitle(if (running) "Stop this download?" else "Remove from queue?")
            .setMessage(job.displayName.take(120))
            .setNegativeButton("Keep", null)
            .setPositiveButton(if (running) "Stop" else "Remove") { _, _ ->
                if (running) {
                    // Stop **this** row only. The notification's Cancel button is the
                    // stop-everything path; a row action must not kill the other downloads.
                    FormatWorker.requestCancel(
                        job.id,
                        FormatWorker.partialDirFor(this, job.url, job.localPath),
                    )
                }
                DownloadQueue.remove(this, job.id)
                reload()
            }
            .show()
    }

    /** Cheap connectivity read, used only to word the row state. */
    private fun isOnline(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return@runCatching false
        val net = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun confirmStopAll() {
        val running = DownloadQueue.inFlightIds().size
        AlertDialog.Builder(this)
            .setTitle("Stop everything?")
            .setMessage(
                if (running > 0) "$running download(s) running and the rest of the queue will be cleared."
                else "The queue will be cleared.",
            )
            .setNegativeButton("Keep", null)
            .setPositiveButton("Stop all") { _, _ ->
                DownloadService.cancel(this)
                DownloadQueue.clear(this)
                reload()
            }
            .show()
    }
}

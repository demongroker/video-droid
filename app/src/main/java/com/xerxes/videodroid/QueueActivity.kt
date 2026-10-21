package com.xerxes.videodroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xerxes.videodroid.ui.Insets
import java.io.File

/**
 * Queue: what is downloading now, what is waiting, and what failed.
 *
 * Rows reflect the same persisted FIFO the service drains.
 * Swipe left  -> cancel / remove (asks first).
 * Swipe right -> share (the finished file, or the link while pending).
 * Tap a failed row -> retry.
 */
class QueueActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var clear: View
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var jobs: List<QueuedJob> = emptyList()
    private val adapter = QueueAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        Insets.applyTo(findViewById(R.id.queueRoot))
        NavHelper.wire(this, R.id.tab_queue)

        list = findViewById(R.id.queueList)
        empty = findViewById(R.id.queueEmpty)
        clear = findViewById(R.id.queueClear)

        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL),
        )
        list.adapter = adapter

        clear.setOnClickListener { confirmStopAll() }
        attachSwipeActions()
    }

    private fun attachSwipeActions() {
        val cb = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val job = jobs.getOrNull(vh.bindingAdapterPosition) ?: return
                if (direction == ItemTouchHelper.LEFT) {
                    cancelOne(job)
                } else {
                    share(job)
                }
                // The row is redrawn from state: keep it if the user says Keep.
                handler.post { reload() }
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(list)
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

    private fun reload() {
        val latest = DownloadQueue.all(this)
        if (latest != jobs) {
            jobs = latest
            adapter.notifyDataSetChanged()
        }
        val none = jobs.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
        clear.visibility = if (none) View.GONE else View.VISIBLE
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
                    FormatWorker.requestCancel()
                    DownloadService.cancel(this)
                }
                DownloadQueue.remove(this, job.id)
                reload()
            }
            .show()
    }

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

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.queueRowTitle)
            val sub: TextView = v.findViewById(R.id.queueRowSub)
            val bar: LinearProgressIndicator = v.findViewById(R.id.queueRowProgress)
            val cancel: View = v.findViewById(R.id.queueRowCancel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_queue, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = jobs.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val job = jobs.getOrNull(position) ?: return
            val running = DownloadQueue.isClaimed(job.id)

            holder.title.text = when {
                job.failed -> "Failed — ${job.failReason.ifBlank { "tap to retry" }}"
                running -> if (job.progress > 0) "Downloading ${job.progress}%" else "Downloading…"
                else -> if (job.progress > 0) "Waiting · ${job.progress}% done" else "Waiting"
            }
            holder.sub.text = "${job.opts.dlQuality} · ${job.displayName}"
            if (running && job.progress > 0) {
                holder.bar.visibility = View.VISIBLE
                holder.bar.setProgressCompat(job.progress, false)
            } else {
                holder.bar.visibility = View.GONE
            }
            holder.cancel.visibility = if (job.failed) View.GONE else View.VISIBLE
            holder.cancel.setOnClickListener { cancelOne(job) }
            holder.itemView.setOnClickListener {
                if (job.failed) retry(job) else openItem(job)
            }
        }
    }
}

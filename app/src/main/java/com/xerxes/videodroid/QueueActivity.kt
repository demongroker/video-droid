package com.xerxes.videodroid

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xerxes.videodroid.ui.Insets

/**
 * Queue: what is downloading now and what is still waiting.
 *
 * Reads the same persisted FIFO the service drains, so rows reflect real state.
 * Each row can be cancelled on its own; the header can stop everything.
 */
class QueueActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var clear: View
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var jobs: List<QueuedJob> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        Insets.applyTo(findViewById(R.id.queueRoot))
        NavHelper.wire(this, R.id.tab_queue)

        list = findViewById(R.id.queueList)
        empty = findViewById(R.id.queueEmpty)
        clear = findViewById(R.id.queueClear)

        clear.setOnClickListener { confirmStopAll() }
        list.adapter = QueueAdapter()

        list.setOnItemClickListener { _, _, position, _ -> openItem(jobs.getOrNull(position)) }
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
        jobs = DownloadQueue.all(this)
        (list.adapter as? QueueAdapter)?.notifyDataSetChanged()
        val none = jobs.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
        clear.visibility = if (none) View.GONE else View.VISIBLE
    }

    private fun openItem(job: QueuedJob?) {
        if (job == null) return
        handler.post {
            runCatching {
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(job.displayName), "video/mp4")
                    },
                )
            }
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

    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount(): Int = jobs.size

        override fun getItem(position: Int): QueuedJob? = jobs.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@QueueActivity).inflate(R.layout.item_queue, parent, false)
            val job = jobs[position]
            val running = DownloadQueue.isClaimed(job.id)

            val title = view.findViewById<TextView>(R.id.queueRowTitle)
            val sub = view.findViewById<TextView>(R.id.queueRowSub)
            val bar = view.findViewById<LinearProgressIndicator>(R.id.queueRowProgress)
            val cancel = view.findViewById<View>(R.id.queueRowCancel)

            title.text = if (running) {
                if (job.progress > 0) "Downloading ${job.progress}%" else "Downloading…"
            } else {
                if (job.progress > 0) "Waiting · ${job.progress}% done" else "Waiting"
            }
            sub.text = "${job.opts.dlQuality} · ${job.displayName}"
            if (running && job.progress > 0) {
                bar.visibility = View.VISIBLE
                bar.setProgressCompat(job.progress, false)
            } else {
                bar.visibility = View.GONE
            }
            cancel.setOnClickListener { cancelOne(job) }
            return view
        }
    }
}

package com.xerxes.videodroid

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.xerxes.videodroid.ui.Insets

/**
 * Queue: what is still waiting to download.
 *
 * Reads the same persisted FIFO the service drains (DownloadQueue), so this is
 * a view of real state — it does not keep its own copy.
 */
class QueueActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        Insets.applyTo(findViewById(R.id.queueRoot))
        NavHelper.wire(this, R.id.tab_queue)

        val list = findViewById<ListView>(R.id.queueList)
        val empty = findViewById<TextView>(R.id.queueEmpty)
        val clear = findViewById<View>(R.id.queueClear)

        val jobs = DownloadQueue.all(this)
        if (jobs.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            clear.visibility = View.GONE
            return
        }

        val labels = jobs.mapIndexed { i, job ->
            val what = job.url.ifEmpty { job.localPath.substringAfterLast('/') }
            val where = job.opts.dlQuality
            val prefix = if (i == 0) "Downloading" else "Waiting"
            "$prefix · $where\n$what"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        clear.setOnClickListener {
            DownloadQueue.clear(this)
            DownloadService.cancel(this)
            recreate()
        }
    }
}

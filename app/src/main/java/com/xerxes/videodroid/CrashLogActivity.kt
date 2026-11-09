package com.xerxes.videodroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xerxes.videodroid.ui.Insets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → About → Crash logs.
 *
 * Lists what [CrashLog] has recorded (newest first), opens one to read it, and offers Copy,
 * Share and Clear all. Nothing is uploaded: Share goes through the Android share sheet and a
 * FileProvider, so the owner chooses the destination.
 */
class CrashLogActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var viewer: View
    private lateinit var viewerTitle: TextView
    private lateinit var viewerText: TextView

    private var entries: List<File> = emptyList()
    private var openFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureScreen.apply(window)
        setContentView(R.layout.activity_crash_logs)
        Insets.applyTo(findViewById(R.id.crashLogsRoot))

        list = findViewById(R.id.crashLogsList)
        empty = findViewById(R.id.crashLogsEmpty)
        viewer = findViewById(R.id.crashLogViewer)
        viewerTitle = findViewById(R.id.crashViewerTitle)
        viewerText = findViewById(R.id.crashViewerText)

        findViewById<View>(R.id.crashLogsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.crashViewerBack).setOnClickListener { closeViewer() }
        findViewById<Button>(R.id.crashLogsClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.crashViewerCopy).setOnClickListener { copyOpen() }
        findViewById<Button>(R.id.crashViewerShare).setOnClickListener { shareOpen() }

        list.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { open(it) }
        }

        reload()
    }

    private fun reload() {
        entries = CrashLog.files(this)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        list.adapter = EntryAdapter(entries)
    }

    private fun open(file: File) {
        openFile = file
        viewerTitle.text = file.name
        viewerText.text = CrashLog.read(file)
        viewer.visibility = View.VISIBLE
        // Opening is enough acknowledgement for the launch banner.
        CrashLog.clearPending(this)
    }

    private fun closeViewer() {
        viewer.visibility = View.GONE
        openFile = null
    }

    private fun copyOpen() {
        val text = viewerText.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("VideoDroid crash log", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareOpen() {
        val file = openFile ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share crash log",
                ),
            )
        }.onFailure {
            Toast.makeText(this, "Could not share: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear all crash logs?")
            .setMessage("This deletes every saved crash and error record on this phone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Clear") { _, _ ->
                val n = CrashLog.clearAll(this)
                closeViewer()
                reload()
                Toast.makeText(this, "Deleted $n log${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
    }

    private inner class EntryAdapter(private val rows: List<File>) : BaseAdapter() {
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(
                android.R.layout.simple_list_item_2,
                parent,
                false,
            )
            val row = rows[position]
            val title = v.findViewById<TextView>(android.R.id.text1)
            val sub = v.findViewById<TextView>(android.R.id.text2)
            val fatal = CrashLog.isCrashFile(row)
            title.text = if (fatal) "Crash · ${row.name}" else "Error · ${row.name}"
            title.setTextColor(if (fatal) 0xFFF87171.toInt() else 0xFFA78BFA.toInt())
            sub.text = "${fmt.format(Date(row.lastModified()))} · ${row.length()} bytes · tap to read"
            sub.setTextColor(0xFF9E9E9E.toInt())
            v.setPadding(dp(20), dp(10), dp(20), dp(10))
            return v
        }

        private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    }
}

package com.xerxes.videodroid

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.xerxes.videodroid.ui.Insets
import java.io.File

/**
 * Library: everything VideoDroid has produced so far.
 *
 * FormatWorker publishes finished files through MediaStore into
 * `Download/VideoDroid`, so this screen reads them back from the same place —
 * the app keeps no second index that could drift.
 */
class LibraryActivity : AppCompatActivity() {

    private data class Item(val uri: Uri, val name: String, val size: Long, val added: Long) {
        fun label(now: Long, ctx: android.content.Context): String {
            val when_ = DateUtils.getRelativeTimeSpanString(added * 1000L, now, DateUtils.MINUTE_IN_MILLIS)
            val size_ = Formatter.formatShortFileSize(ctx, size)
            return "$name\n$size_ · $when_"
        }
    }

    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        Insets.applyTo(findViewById(R.id.libraryRoot))

        list = findViewById(R.id.libraryList)
        empty = findViewById(R.id.libraryEmpty)
        NavHelper.wire(this, R.id.tab_library)
        findViewById<View>(R.id.libraryBack).setOnClickListener { finish() }

        val items = loadItems()
        if (items.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        val now = System.currentTimeMillis()
        val labels = items.map { it.label(now, this) }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, position, _ -> open(items[position]) }
    }

    private fun open(item: Item) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        } catch (_: Throwable) {
            // Nothing can play it — fall back to the system share sheet.
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Open with"))
        }
    }

    private fun loadItems(): List<Item> =
        if (Build.VERSION.SDK_INT >= 29) loadFromMediaStore() else loadFromDisk()

    private fun loadFromMediaStore(): List<Item> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${OUT_DIR}%")
        val out = ArrayList<Item>()
        try {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(
                        Item(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, id,
                            ),
                            name = c.getString(nameCol) ?: "video.mp4",
                            size = c.getLong(sizeCol),
                            added = c.getLong(dateCol),
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            // Fall through to an empty list rather than crashing the screen.
        }
        return out
    }

    /** Pre-Q: no RELATIVE_PATH column, so read the folder directly. */
    @Suppress("DEPRECATION")
    private fun loadFromDisk(): List<Item> {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), OUT_DIR)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .map {
                Item(
                    uri = Uri.fromFile(it),
                    name = it.name,
                    size = it.length(),
                    added = it.lastModified() / 1000L,
                )
            }
    }

    companion object {
        private const val OUT_DIR = "VideoDroid"
    }

    override fun onResume() {
        super.onResume()
        NavHelper.sync(this, R.id.tab_library)
    }
}

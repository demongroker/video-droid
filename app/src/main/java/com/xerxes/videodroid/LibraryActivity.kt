package com.xerxes.videodroid

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

/** Finished downloads, as a thumbnail grid with a storage bar (v2). */
class LibraryActivity : AppCompatActivity() {

    private data class Item(val uri: Uri, val name: String, val size: Long)

    private lateinit var grid: GridView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        Insets.applyTo(findViewById(R.id.libraryRoot))
        NavHelper.sync(this, R.id.tab_library)

        grid = findViewById(R.id.libraryGrid)
        empty = findViewById(R.id.libraryEmpty)

        val items = loadItems()
        grid.adapter = GridAdapter(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        grid.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }.onFailure { share(item.uri) }
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { share(it.uri) }
            true
        }

        updateStorage()
    }

    override fun onResume() {
        super.onResume()
        NavHelper.sync(this, R.id.tab_library)
    }

    private fun share(uri: Uri) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share"))
        }
    }

    private fun updateStorage() {
        val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }
            .getOrNull() ?: return
        val free = stat.availableBytes
        val total = stat.totalBytes
        if (total <= 0) return
        val usedPct = (((total - free).toDouble() / total) * 100).toInt().coerceIn(0, 100)
        findViewById<TextView>(R.id.storageText)?.text = String.format(
            Locale.US,
            "%s free of %s",
            human(free),
            human(total),
        )
        findViewById<android.widget.ProgressBar>(R.id.storageBar)?.progress = usedPct
    }

    private fun human(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }

    private fun loadItems(): List<Item> {
        val out = ArrayList<Item>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val args = arrayOf("%VideoDroid%")

        runCatching {
            contentResolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns._ID} DESC")
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        out.add(Item(uri, c.getString(nameCol) ?: "video", c.getLong(sizeCol)))
                    }
                }
        }
        return out
    }

    private inner class GridAdapter(private val items: List<Item>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(R.layout.item_library, parent, false)
            val item = items[position]
            val thumb = view.findViewById<ImageView>(R.id.libThumb)
            val label = view.findViewById<TextView>(R.id.libName)

            label.text = item.name
            thumb.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    thumb.setImageBitmap(
                        contentResolver.loadThumbnail(item.uri, Size(256, 256), null),
                    )
                }.onFailure { thumb.setImageResource(R.drawable.ic_library) }
            } else {
                thumb.setImageResource(R.drawable.ic_library)
            }
            return view
        }
    }
}

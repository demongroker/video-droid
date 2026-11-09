package com.xerxes.videodroid

import android.content.ContentUris
import android.content.ContentValues
import com.xerxes.videodroid.ui.Insets
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Finished downloads, as a thumbnail grid with a storage bar (v2). */
class LibraryActivity : AppCompatActivity() {

    private data class Item(val uri: Uri, val name: String, val size: Long)

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var items: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        Insets.applyTo(findViewById(R.id.libraryRoot))
        NavHelper.sync(this, R.id.tab_library)

        grid = findViewById(R.id.libraryGrid)
        empty = findViewById(R.id.libraryEmpty)

        reload()

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

        findViewById<View>(R.id.findDuplicates)?.setOnClickListener { scanDuplicates() }

        updateStorage()
    }

    override fun onResume() {
        super.onResume()
        NavHelper.sync(this, R.id.tab_library)
        reload()
    }

    private fun reload() {
        items = loadItems()
        grid.adapter = GridAdapter(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        updateStorage()
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

    // ---- duplicates -------------------------------------------------------------

    /**
     * Two-gate duplicate cleanup: our own dialog first, and even then nothing is deleted —
     * the extras are **moved** to `Download/VideoDroid/duplicates`, which is reversible and
     * hides them from this grid.
     */
    private fun scanDuplicates() {
        val byId = items.associateBy { idOf(it.uri).toString() }
        val entries = items.map { DuplicateScan.Entry(idOf(it.uri).toString(), it.name) }
        val groups = DuplicateScan.duplicates(entries)

        if (groups.isEmpty()) {
            Toast.makeText(this, "No duplicate videos found", Toast.LENGTH_SHORT).show()
            return
        }

        val extras = DuplicateScan.extras(groups)
        val preview = groups.take(3).joinToString("\n") { g ->
            "• " + byId[g.first().id]?.name.orEmpty().take(40) + "  (+${g.size - 1})"
        }
        val more = if (groups.size > 3) "\n…and ${groups.size - 3} more" else ""

        AlertDialog.Builder(this)
            .setTitle("${extras.size} duplicate${if (extras.size == 1) "" else "s"} found")
            .setMessage(
                "$preview$more\n\nMove the extra copies to Downloads/VideoDroid/duplicates? " +
                    "Nothing is deleted.",
            )
            .setNegativeButton("Keep all", null)
            .setPositiveButton("Move extras") { _, _ -> quarantine(extras) }
            .show()
    }

    private fun quarantine(extras: List<DuplicateScan.Entry>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Needs Android 10 or newer", Toast.LENGTH_LONG).show()
            return
        }
        var moved = 0
        var failed = 0
        for (e in extras) {
            val rawId = e.id.toLongOrNull() ?: continue
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rawId)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, DUPLICATES_RELATIVE_PATH)
            }
            val ok = runCatching { contentResolver.update(uri, values, null, null) > 0 }
                .getOrDefault(false)
            if (ok) moved++ else failed++
        }
        reload()
        val msg = when {
            moved == 0 ->
                "Could not move any duplicates — nothing was deleted. Use Files → Downloads/VideoDroid."
            failed == 0 -> "Moved $moved duplicate${if (moved == 1) "" else "s"} to VideoDroid/duplicates"
            else -> "Moved $moved, could not move $failed (nothing deleted)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun idOf(uri: Uri): Long =
        runCatching { uri.lastPathSegment?.toLongOrNull() ?: -1L }.getOrDefault(-1L)

    // ---- storage / listing -------------------------------------------------------

    private fun updateStorage() {
        val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }
            .getOrNull() ?: return
        val free = stat.availableBytes
        val total = stat.totalBytes
        if (total <= 0) return
        val usedPct = (((total - free).toDouble() / total) * 100).toInt().coerceIn(0, 100)
        findViewById<TextView>(R.id.storageText)?.text = String.format(
            Locale.US,
            "%s free of %s (device storage)",
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
        // Quarantined copies live in a subfolder and must not show up here.
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? AND ${MediaStore.MediaColumns.DATA} NOT LIKE ?"
        }
        val args = arrayOf("%VideoDroid%", "%duplicates%")

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

    private inner class GridAdapter(private val rows: List<Item>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(R.layout.item_library, parent, false)
            val item = rows[position]
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

    private companion object {
        const val DUPLICATES_RELATIVE_PATH = "Download/VideoDroid/duplicates"
    }
}

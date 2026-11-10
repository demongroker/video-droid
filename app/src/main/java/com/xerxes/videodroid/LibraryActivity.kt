package com.xerxes.videodroid

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xerxes.videodroid.ui.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Finished downloads: thumbnail grid with a storage bar, sort, duration/size badges,
 * long-press multi-select (share / move / delete) and an optional biometric lock.
 *
 * Nothing here deletes anything without an explicit confirmation, and "move" always copies
 * and verifies before the source row is removed.
 */
class LibraryActivity : AppCompatActivity() {

    private data class Item(
        val uri: Uri,
        val name: String,
        val size: Long,
        val durationMs: Long,
        val dateAdded: Long,
    ) {
        val id: Long get() = uri.lastPathSegment?.toLongOrNull() ?: -1L
    }

    private enum class Sort(val pref: String, val label: String) {
        DATE("date", "Sort: newest"),
        SIZE("size", "Sort: largest"),
        NAME("name", "Sort: name"),
    }

    private lateinit var grid: GridView
    private lateinit var empty: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var sortButton: TextView

    private var items: List<Item> = emptyList()
    private var moving = false
    private var sort = Sort.DATE
    private val selected = LinkedHashSet<Long>()
    private var adapter: GridAdapter? = null
    private lateinit var viewToggleButton: TextView
    private var listMode = false

    private val movePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val tree = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && tree != null) {
                performMove(tree)
            } else {
                toast("Move cancelled — nothing was changed")
            }
        }

    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                clearSelection()
                reload()
            } else {
                toast("Delete cancelled — nothing was removed")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stage 4: thumbnails must never appear in the recent-apps snapshot.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(R.layout.activity_library)
        Insets.applyTo(findViewById(R.id.libraryRoot))
        NavHelper.sync(this, R.id.tab_library)

        grid = findViewById(R.id.libraryGrid)
        empty = findViewById(R.id.libraryEmpty)
        selectionBar = findViewById(R.id.librarySelectionBar)
        selectionCount = findViewById(R.id.librarySelectionCount)
        sortButton = findViewById(R.id.librarySort)

        sort = Sort.entries.firstOrNull {
            it.pref == getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SORT, "date")
        } ?: Sort.DATE
        renderSortButton()

        grid.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            if (selected.isNotEmpty()) toggleSelection(item) else open(item)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { toggleSelection(it) }
            true
        }

        findViewById<View>(R.id.findDuplicates).setOnClickListener { scanDuplicates() }
        sortButton.setOnClickListener { showSortMenu() }
        viewToggleButton = findViewById(R.id.libraryViewToggle)
        listMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LIST_MODE, false)
        applyViewMode()
        viewToggleButton.setOnClickListener {
            listMode = !listMode
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_LIST_MODE, listMode).apply()
            Haptics.tick(this)
            applyViewMode()
        }

        findViewById<View>(R.id.librarySelShare).setOnClickListener { shareSelected() }
        findViewById<View>(R.id.librarySelMove).setOnClickListener { pickMoveTarget() }
        findViewById<View>(R.id.librarySelDelete).setOnClickListener { confirmDeleteSelected() }
        findViewById<View>(R.id.librarySelClose).setOnClickListener { clearSelection() }

        updateStorage()
        gateThenLoad()
    }

    override fun onResume() {
        super.onResume()
        NavHelper.sync(this, R.id.tab_library)
        if (unlocked) reload()
    }

    // ---- lock gate ---------------------------------------------------------------

    private var unlocked = false

    private fun gateThenLoad() {
        if (!LibraryLock.enabled(this)) {
            unlocked = true
            reload()
            return
        }
        grid.visibility = View.INVISIBLE
        empty.visibility = View.GONE
        LibraryLock.authenticate(
            this,
            onSuccess = {
                unlocked = true
                reload()
            },
            onFail = {
                toast("Library locked")
                finish()
            },
            onNoCredential = {
                // No screen lock on the device: the gate cannot be enforced. Say so and open.
                toast("Set a phone PIN or fingerprint to lock the Library")
                unlocked = true
                reload()
            },
        )
    }

    // ---- listing / sorting --------------------------------------------------------

    private fun reload() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadItems() }
            if (isFinishing || isDestroyed) return@launch
            items = sortItems(loaded)
            adapter = GridAdapter(items).also { grid.adapter = it }
            grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            updateStorage()
            syncSelectionUi()
        }
    }

    private fun sortItems(list: List<Item>): List<Item> = when (sort) {
        Sort.DATE -> list.sortedByDescending { it.dateAdded }
        Sort.SIZE -> list.sortedByDescending { it.size }
        Sort.NAME -> list.sortedBy { it.name.lowercase() }
    }

    /** Grid (2 columns) or a single-column list; remembered between launches. */
    private fun applyViewMode() {
        grid.numColumns = if (listMode) 1 else 2
        viewToggleButton.text = if (listMode) "Grid" else "List"
    }

    private fun renderSortButton() {
        sortButton.text = sort.label
    }

    private fun showSortMenu() {
        val popup = PopupMenu(this, sortButton)
        Sort.entries.forEach { popup.menu.add(0, it.ordinal, it.ordinal, it.label) }
        popup.setOnMenuItemClickListener { mi ->
            sort = Sort.entries[mi.itemId]
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SORT, sort.pref).apply()
            renderSortButton()
            items = sortItems(items)
            adapter?.let { it.rows = items; it.notifyDataSetChanged() }
            true
        }
        popup.show()
    }

    private fun open(item: Item) {
        // Vela when it is installed, otherwise the system player, otherwise share.
        if (PlayerChoice.play(this, item.uri)) return
        shareOne(item.uri)
    }

    // ---- selection ----------------------------------------------------------------

    private fun toggleSelection(item: Item) {
        if (!selected.add(item.id)) selected.remove(item.id)
        adapter?.notifyDataSetChanged()
        syncSelectionUi()
    }

    private fun clearSelection() {
        selected.clear()
        adapter?.notifyDataSetChanged()
        syncSelectionUi()
    }

    private fun syncSelectionUi() {
        val n = selected.size
        selectionBar.visibility = if (n == 0) View.GONE else View.VISIBLE
        selectionCount.text = Texts.selected(n)
    }

    private fun selectedItems(): List<Item> = items.filter { selected.contains(it.id) }

    private fun shareSelected() {
        val uris = ArrayList(selectedItems().map { it.uri })
        if (uris.isEmpty()) return
        if (uris.size == 1) return shareOne(uris.first())
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "video/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share ${uris.size} videos",
                ),
            )
        }
    }

    private fun shareOne(uri: Uri) {
        runCatching {
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
        }
    }

    // ---- move ---------------------------------------------------------------------

    /** Copy → verify size → only then remove the original. Never a blind rename. */
    private fun pickMoveTarget() {
        if (selectedItems().isEmpty()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        runCatching { movePicker.launch(intent) }
            .onFailure { toast("No folder picker available on this device") }
    }

    private fun performMove(treeUri: Uri) {
        val targets = selectedItems()
        if (targets.isEmpty() || moving) return
        Haptics.tick(this)
        moving = true
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Moving…")
            .setMessage("${targets.size} file${if (targets.size == 1) "" else "s"}")
            .setCancelable(false)
            .create()
        progress.show().also { d -> SecureScreen.secure(d) }

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { it to copyThenRemove(treeUri, it) }
            }
            runCatching { progress.dismiss() }
            moving = false
            clearSelection()
            reload()
            val ok = results.count { it.second }
            val bad = results.size - ok
            toast(
                when {
                    bad == 0 -> "Moved $ok file${if (ok == 1) "" else "s"}"
                    ok == 0 -> "Could not move anything — originals untouched"
                    else -> "Moved $ok, kept $bad (copy not verified)"
                },
            )
        }
    }

    private fun copyThenRemove(treeUri: Uri, item: Item): Boolean {
        val resolver = contentResolver
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val mime = resolver.getType(item.uri) ?: "video/mp4"
            // Hash the source first: if we cannot read it, we must not move it.
            val sourceHash = FileDigest.sha256(resolver, item.uri) ?: return false
            val dest = DocumentsContract.createDocument(resolver, parent, mime, item.name)
                ?: return false
            var copied = 0L
            resolver.openInputStream(item.uri)?.use { input ->
                resolver.openOutputStream(dest)?.use { out ->
                    copied = input.copyTo(out)
                }
            }
            if (copied <= 0 || (item.size > 0 && copied != item.size)) {
                runCatching { DocumentsContract.deleteDocument(resolver, dest) }
                return false
            }
            // Stage 5b: identical bytes, not just identical length. A copy that differs by one
            // byte is a corrupt file, and deleting the original then would lose the video.
            val destHash = FileDigest.sha256(resolver, dest)
            if (destHash == null || destHash != sourceHash) {
                runCatching { DocumentsContract.deleteDocument(resolver, dest) }
                toast("Copy did not match the original — nothing was moved")
                return false
            }
            // Verified by hash. Only now touch the original.
            runCatching { resolver.delete(item.uri, null, null) }
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ---- delete -------------------------------------------------------------------

    private fun confirmDeleteSelected() {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${Texts.videos(targets.size)}?")
            .setMessage("This removes the file from your phone. It cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                Haptics.thud(this)
                deleteItems(targets)
            }
            .show()
                .also { d -> SecureScreen.secure(d) }
    }

    private fun deleteItems(targets: List<Item>) {
        val uris = targets.map { it.uri }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The system shows its own confirmation for media deletes; two gates, not one.
            runCatching {
                val pi: PendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure {
                // Fall back to a direct delete if the system request is unavailable.
                directDelete(uris)
            }
        } else {
            directDelete(uris)
        }
    }

    /**
     * Delete for Android 7-10 (and the fallback path everywhere).
     *
     * Only ever reached from [confirmDeleteSelected] — the confirmation dialog is the gate,
     * and Android 10 can still ask for its own consent through RecoverableSecurityException,
     * so that intent is launched rather than being swallowed as a silent failure.
     */
    private fun directDelete(uris: List<Uri>) {
        var ok = 0
        var consent: IntentSender? = null
        uris.forEach { uri ->
            try {
                if (contentResolver.delete(uri, null, null) > 0) ok++
            } catch (se: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val recoverable = se as? android.app.RecoverableSecurityException
                    consent = recoverable?.userAction?.actionIntent?.intentSender ?: consent
                }
            } catch (t: Throwable) {
                // Leave the row alone; the count below reports only real deletions.
            }
        }
        if (consent != null) {
            runCatching {
                deleteRequest.launch(IntentSenderRequest.Builder(consent).build())
            }.onFailure { toast("Android needs you to confirm this delete in Files") }
            return
        }
        toast("Deleted $ok of ${uris.size}")
        clearSelection()
        reload()
    }

    // ---- duplicates ----------------------------------------------------------------

    private fun scanDuplicates() {
        val byId = items.associateBy { it.id.toString() }
        val entries = items.map { DuplicateScan.Entry(it.id.toString(), it.name) }
        val groups = DuplicateScan.duplicates(entries)

        if (groups.isEmpty()) {
            toast("No duplicate videos found")
            return
        }

        val extras = DuplicateScan.extras(groups)
        val preview = groups.take(3).joinToString("\n") { g ->
            "• " + byId[g.first().id]?.name.orEmpty().take(40) + "  (+${g.size - 1})"
        }
        val more = if (groups.size > 3) "\n…and ${groups.size - 3} more" else ""

        MaterialAlertDialogBuilder(this)
            .setTitle("${Texts.duplicates(extras.size)} found")
            .setMessage(
                "$preview$more\n\nMove the extra copies to Downloads/VideoDroid/duplicates? " +
                    "Nothing is deleted.",
            )
            .setNegativeButton("Keep all", null)
            .setPositiveButton("Move extras") { _, _ -> quarantine(extras) }
            .show()
                .also { d -> SecureScreen.secure(d) }
    }

    private fun quarantine(extras: List<DuplicateScan.Entry>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("Needs Android 10 or newer")
            return
        }
        if (moving) return
        moving = true

        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Moving duplicates…")
            .setMessage("${extras.size} file${if (extras.size == 1) "" else "s"}")
            .setCancelable(false)
            .create()
        progress.show().also { d -> SecureScreen.secure(d) }

        lifecycleScope.launch {
            val movedTo = withContext(Dispatchers.IO) { moveExtras(extras) }
            runCatching { progress.dismiss() }
            moving = false
            reload()
            val (moved, failed) = movedTo
            toast(
                when {
                    moved == 0 ->
                        "Could not move any duplicates — nothing was deleted. Use Files → Downloads/VideoDroid."
                    failed == 0 -> "Moved $moved duplicate${if (moved == 1) "" else "s"} to VideoDroid/duplicates"
                    else -> "Moved $moved, could not move $failed (nothing deleted)"
                },
            )
        }
    }

    private fun moveExtras(extras: List<DuplicateScan.Entry>): Pair<Int, Int> {
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
        return moved to failed
    }

    // ---- storage / listing -----------------------------------------------------------

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

    /** Storage numbers keep their own formatter; the badges use the tested helper. */
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
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED,
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
            contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    out.add(
                        Item(
                            uri = uri,
                            name = c.getString(nameCol) ?: "video",
                            size = c.getLong(sizeCol),
                            durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                            dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class GridAdapter(var rows: List<Item>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(R.layout.item_library, parent, false)
            val item = rows[position]
            val thumb = view.findViewById<ImageView>(R.id.libThumb)
            val label = view.findViewById<TextView>(R.id.libName)
            val size = view.findViewById<TextView>(R.id.libSize)
            val duration = view.findViewById<TextView>(R.id.libDuration)
            val overlay = view.findViewById<View>(R.id.libSelectedOverlay)
            val check = view.findViewById<TextView>(R.id.libCheck)

            label.text = item.name
            size.text = LibraryFormat.size(item.size)
            duration.text = LibraryFormat.duration(item.durationMs)

            val isSelected = selected.contains(item.id)
            overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            check.visibility = if (isSelected) View.VISIBLE else View.GONE

            // Stage 5b: decoded on a worker thread, never in getView().
            LibraryThumbLoader.load(this@LibraryActivity, item.uri, thumb)
            return view
        }
    }

    private companion object {
        const val DUPLICATES_RELATIVE_PATH = "Download/VideoDroid/duplicates"
        const val PREFS = "videodroid"
        const val KEY_SORT = "lib_sort"
        const val KEY_LIST_MODE = "lib_list_mode"
    }
}

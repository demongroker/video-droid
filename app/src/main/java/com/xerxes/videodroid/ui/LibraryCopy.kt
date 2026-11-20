package com.xerxes.videodroid.ui

import com.xerxes.videodroid.Texts
import java.util.Locale

/** How the Library is ordered. Persisted under "lib_sort"; labels are the button copy. */
enum class LibrarySort(val pref: String, val label: String) {
    DATE("date", "Sort: newest"),
    SIZE("size", "Sort: largest"),
    NAME("name", "Sort: name");

    companion object {
        fun fromPref(raw: String?): LibrarySort =
            entries.firstOrNull { it.pref == raw } ?: DATE
    }
}

/**
 * Pure copy + rules for the Library screen, mirroring [QueueCopy].
 *
 * The storage line, the sort/toggle labels and the empty state are contracts with the owner's
 * field reports; keeping them here makes them unit-testable instead of buried in a binder.
 * Sorting is generic so it can be pinned on plain data without a MediaStore.
 */
object LibraryCopy {

    const val EMPTY_TITLE = "Nothing downloaded yet"
    const val EMPTY_HINT = "Paste a link on the Download tab to start."
    const val EMPTY_ACTION = "Go to Download"
    const val LOCKED_HINT = "Unlock to see your downloads"

    /** The toggle offers the OTHER view: in list mode the button says "Grid". */
    fun viewToggleLabel(listMode: Boolean): String = if (listMode) "Grid" else "List"

    fun selectionCount(n: Int): String = Texts.selected(n)

    /** Storage numbers keep their own formatter; the badges use LibraryFormat. */
    fun human(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }

    fun storageText(freeBytes: Long, totalBytes: Long): String = String.format(
        Locale.US,
        "%s free of %s (device storage)",
        human(freeBytes),
        human(totalBytes),
    )

    fun usedPercent(freeBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0) return 0
        return (((totalBytes - freeBytes).toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
    }

    /** Newest/largest descending, name ascending and case-insensitive. */
    fun <T> sort(
        items: List<T>,
        sort: LibrarySort,
        dateOf: (T) -> Long,
        sizeOf: (T) -> Long,
        nameOf: (T) -> String,
    ): List<T> = when (sort) {
        LibrarySort.DATE -> items.sortedByDescending(dateOf)
        LibrarySort.SIZE -> items.sortedByDescending(sizeOf)
        LibrarySort.NAME -> items.sortedBy { nameOf(it).lowercase() }
    }
}
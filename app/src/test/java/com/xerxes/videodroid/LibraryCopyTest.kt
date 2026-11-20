package com.xerxes.videodroid

import com.xerxes.videodroid.ui.LibraryCopy
import com.xerxes.videodroid.ui.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library copy + rules, pinned off-device: sort labels and persistence keys, the storage
 * line, the view toggle, and the ordering itself (generic, so no MediaStore is needed).
 */
class LibraryCopyTest {

    private data class Row(val name: String, val size: Long, val date: Long)

    private val rows = listOf(
        Row("beta.mp4", 300L, 30L),
        Row("Alpha.mp4", 100L, 10L),
        Row("gamma.mp4", 200L, 20L),
    )

    @Test
    fun sortFromPrefFallsBackToDate() {
        assertEquals(LibrarySort.DATE, LibrarySort.fromPref("date"))
        assertEquals(LibrarySort.SIZE, LibrarySort.fromPref("size"))
        assertEquals(LibrarySort.NAME, LibrarySort.fromPref("name"))
        assertEquals(LibrarySort.DATE, LibrarySort.fromPref(null))
        assertEquals(LibrarySort.DATE, LibrarySort.fromPref("junk"))
    }

    @Test
    fun sortLabelsAreTheButtonCopy() {
        assertEquals("Sort: newest", LibrarySort.DATE.label)
        assertEquals("Sort: largest", LibrarySort.SIZE.label)
        assertEquals("Sort: name", LibrarySort.NAME.label)
    }

    @Test
    fun dateSortIsNewestFirst() {
        val sorted = LibraryCopy.sort(rows, LibrarySort.DATE, { it.date }, { it.size }, { it.name })
        assertEquals(listOf("beta.mp4", "gamma.mp4", "Alpha.mp4"), sorted.map { it.name })
    }

    @Test
    fun sizeSortIsLargestFirst() {
        val sorted = LibraryCopy.sort(rows, LibrarySort.SIZE, { it.date }, { it.size }, { it.name })
        assertEquals(listOf("beta.mp4", "gamma.mp4", "Alpha.mp4"), sorted.map { it.name })
    }

    @Test
    fun nameSortIsCaseInsensitiveAscending() {
        val sorted = LibraryCopy.sort(rows, LibrarySort.NAME, { it.date }, { it.size }, { it.name })
        assertEquals(listOf("Alpha.mp4", "beta.mp4", "gamma.mp4"), sorted.map { it.name })
    }

    @Test
    fun viewToggleOffersTheOtherView() {
        assertEquals("Grid", LibraryCopy.viewToggleLabel(listMode = true))
        assertEquals("List", LibraryCopy.viewToggleLabel(listMode = false))
    }

    @Test
    fun selectionCountNeverTakesAPluralNoun() {
        assertEquals("1 selected", LibraryCopy.selectionCount(1))
        assertEquals("3 selected", LibraryCopy.selectionCount(3))
    }

    @Test
    fun storageLineMatchesTheFieldFormat() {
        val free = 20L * 1024 * 1024 * 1024
        val total = 64L * 1024 * 1024 * 1024
        assertEquals("20.0 GB free of 64.0 GB (device storage)", LibraryCopy.storageText(free, total))
        assertEquals(68, LibraryCopy.usedPercent(free, total))
    }

    @Test
    fun storagePercentIsHonestAtTheEdges() {
        assertEquals(0, LibraryCopy.usedPercent(100L, 100L))
        assertEquals(100, LibraryCopy.usedPercent(0L, 100L))
        assertEquals(0, LibraryCopy.usedPercent(0L, 0L))
        assertEquals(100, LibraryCopy.usedPercent(-5L, 100L))
    }

    @Test
    fun humanSizeKeepsItsOwnFormatter() {
        assertEquals("1.5 GB", LibraryCopy.human((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("512 MB", LibraryCopy.human(512L * 1024 * 1024))
        // %.0f rounds: 700 KB is under 1 MB and reads as "1 MB" — the formatter's contract.
        assertEquals("1 MB", LibraryCopy.human(700L * 1024))
    }

    @Test
    fun emptyStateHasOneConcreteNextAction() {
        assertEquals("Nothing downloaded yet", LibraryCopy.EMPTY_TITLE)
        assertTrue(LibraryCopy.EMPTY_HINT.contains("Download tab"))
        assertEquals("Go to Download", LibraryCopy.EMPTY_ACTION)
    }
}
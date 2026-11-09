package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stage 4: the badges on every Library thumbnail. */
class LibraryFormatTest {

    @Test
    fun formatsDurations() {
        assertEquals("", LibraryFormat.duration(0))
        assertEquals("", LibraryFormat.duration(-5))
        assertEquals("0:05", LibraryFormat.duration(5_000))
        assertEquals("1:15", LibraryFormat.duration(75_000))
        assertEquals("1:02:05", LibraryFormat.duration(3_725_000))
    }

    @Test
    fun formatsSizes() {
        assertEquals("", LibraryFormat.size(0))
        // %.0f rounds, so half a megabyte reads as 1 MB — assert the real behaviour.
        assertEquals("1 MB", LibraryFormat.size(512L * 1024))
        assertEquals("425 MB", LibraryFormat.size(425L * 1024 * 1024))
        assertEquals("1.5 GB", LibraryFormat.size((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}

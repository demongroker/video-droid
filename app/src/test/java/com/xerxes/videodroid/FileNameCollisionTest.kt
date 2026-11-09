package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 5b: a name collision must never overwrite an existing file. */
class FileNameCollisionTest {

    @Test
    fun plainNameWhenFree() {
        assertEquals(
            "title_20261109_720p.mp4",
            FormatWorker.numberedName("title_20261109_720p", "mp4") { false },
        )
    }

    @Test
    fun secondCopyIsSuffixedTwo() {
        val taken = setOf("title_20261109_720p.mp4")
        assertEquals(
            "title_20261109_720p_2.mp4",
            FormatWorker.numberedName("title_20261109_720p", "mp4") { it in taken },
        )
    }

    @Test
    fun thirdCopyIsSuffixedThree() {
        val taken = setOf(
            "title_20261109_720p.mp4",
            "title_20261109_720p_2.mp4",
        )
        assertEquals(
            "title_20261109_720p_3.mp4",
            FormatWorker.numberedName("title_20261109_720p", "mp4") { it in taken },
        )
    }

    @Test
    fun fallsBackToTimestampPastNinetyNine() {
        val name = FormatWorker.numberedName("t", "mp4") { true }
        assertTrue("expected a timestamped name, got $name", Regex("""^t_\d{10,}\.mp4$""").matches(name))
    }
}

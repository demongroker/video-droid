package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every count the UI shows has to be correct for one. */
class TextsTest {

    @Test
    fun videosPluralisesCorrectly() {
        assertEquals("1 video", Texts.videos(1))
        assertEquals("2 videos", Texts.videos(2))
        assertEquals("0 videos", Texts.videos(0))
    }

    @Test
    fun otherNouns() {
        assertEquals("1 file", Texts.files(1))
        assertEquals("3 files", Texts.files(3))
        assertEquals("1 duplicate", Texts.duplicates(1))
        assertEquals("4 duplicates", Texts.duplicates(4))
        assertEquals("1 log", Texts.logs(1))
        assertEquals("2 logs", Texts.logs(2))
        assertEquals("1 job", Texts.jobs(1))
        assertEquals("5 jobs", Texts.jobs(5))
    }

    @Test
    fun selectedNeverPluralises() {
        assertEquals("1 selected", Texts.selected(1))
        assertEquals("2 selected", Texts.selected(2))
    }
}

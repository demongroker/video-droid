package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Duplicate detection for the Library "Find duplicates" action. */
class DuplicateScanTest {

    private fun e(id: String, name: String) = DuplicateScan.Entry(id, name)

    @Test
    fun `collision suffix does not hide a duplicate`() {
        val entries = listOf(
            e("2", "AvLoveVI_20261109_861035_text_720p_Original_1731234567890.mp4"),
            e("1", "AvLoveVI_20261109_861035_text_720p_Original.mp4"),
        )
        val groups = DuplicateScan.duplicates(entries)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
        // The clean name is kept even though the timestamped copy has the higher id.
        assertEquals("1", groups[0].first().id)
        assertEquals(
            listOf(e("2", "AvLoveVI_20261109_861035_text_720p_Original_1731234567890.mp4")),
            DuplicateScan.extras(groups),
        )
    }

    @Test
    fun `same post under two different names groups by status tag`() {
        // native extractor name vs the FxTwitter fallback name
        val entries = listOf(
            e("2", "AvLoveVI_20261109_861035_720p_Original.mp4"),
            e("1", "someOtherText_861035_720p_Original.mp4"),
        )
        val groups = DuplicateScan.duplicates(entries)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `different videos are not duplicates`() {
        val entries = listOf(
            e("1", "video_deadbeef_720p_Original.mp4"),
            e("2", "video_cafebabe_720p_Original.mp4"),
            e("3", "chudai_720p_Original.mp4"),
        )
        assertTrue(DuplicateScan.duplicates(entries).isEmpty())
    }

    @Test
    fun `the same name twice is one group of two`() {
        val entries = listOf(e("2", "clip.mp4"), e("1", "clip.mp4"))
        val groups = DuplicateScan.duplicates(entries)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `status tag only comes from names this app wrote`() {
        assertEquals("861035", DuplicateScan.statusTagOf("AvLoveVI_20261109_861035_text_720p_Original.mp4"))
        assertNull("an arbitrary 6-digit number must not count", DuplicateScan.statusTagOf("holiday_2024_500400.mp4"))
        assertNull(DuplicateScan.statusTagOf("video_720p_Original.mp4"))
    }

    @Test
    fun `key strips only a 13 digit suffix`() {
        assertEquals("clip_720p_original.mp4", DuplicateScan.key("clip_720p_Original_1731234567890.mp4"))
        assertEquals("clip_720p_original.mp4", DuplicateScan.key("CLIP_720p_Original.mp4"))
        assertEquals("clip_123.mp4", DuplicateScan.key("clip_123.mp4"))
    }
}

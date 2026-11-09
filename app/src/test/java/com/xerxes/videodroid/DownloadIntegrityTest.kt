package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting the network mid-download used to end in "Saved" over a truncated file. Nothing may
 * reach MediaStore — and therefore the Library — unless it is complete and readable.
 */
class DownloadIntegrityTest {

    private fun check(
        name: String = "clip_720p_Original.mp4",
        size: Long = 5_000_000L,
        expected: Long = 5_000_000L,
        w: Int = 1280,
        h: Int = 720,
        d: Double = 42.0,
    ) = DownloadIntegrity.rejection(name, size, expected, w, h, d)

    @Test
    fun `a complete readable file is accepted`() {
        assertNull(check())
        assertNull("estimate may be slightly high", check(size = 9_100_000, expected = 10_000_000))
        assertNull("unknown advertised size", check(expected = 0))
    }

    @Test
    fun `a partial file is refused`() {
        assertEquals("partial file", check(name = "clip_720p_Original.mp4.part"))
        assertEquals("partial file", check(name = "clip.part.mp4"))
    }

    @Test
    fun `an empty or tiny file is refused`() {
        assertEquals("only 0 bytes", check(size = 0))
        assertEquals("only 900 bytes", check(size = 900))
    }

    @Test
    fun `a transfer that ended early is refused`() {
        // 30 % of the advertised size — exactly what a dropped connection leaves behind.
        val why = check(size = 3_000_000, expected = 10_000_000)
        assertNotNull(why)
        assertTrue("expected a 'short:' reason, got $why", why!!.startsWith("short:"))
    }

    @Test
    fun `an unreadable container is refused even when the size looks right`() {
        assertEquals(
            "container unreadable",
            check(w = 0, h = 0, d = 0.0),
        )
    }

    @Test
    fun `a readable container with unknown size metadata is accepted`() {
        assertNull(check(size = 2_000_000, expected = 0, w = 640, h = 360, d = 12.5))
    }

    @Test
    fun `the queue row message is honest`() {
        assertEquals(
            "Download incomplete — nothing was saved (only 0 bytes)",
            DownloadIntegrity.failureMessage("only 0 bytes"),
        )
        assertTrue(
            "the retry classifier must treat this as network-shaped",
            DownloadQueue.isRetryableReason(DownloadIntegrity.failureMessage("short: 3 of ~10 bytes")),
        )
    }
}

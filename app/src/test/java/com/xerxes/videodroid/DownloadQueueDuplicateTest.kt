package com.xerxes.videodroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Duplicate queue rows: the same X post pasted with a `?s=09` share tail (or as a
 * twitter.com link) must not be accepted twice — that is how one video became two files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DownloadQueueDuplicateTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun opts() = FormatOptions(dlQuality = "720p", exportHeight = 1080, aspect = "original")

    private fun enqueue(url: String): Int =
        DownloadQueue.enqueue(ctx, QueuedJob(url = url, opts = opts()))

    @Before
    fun clean() = DownloadQueue.clear(ctx)

    @Test
    fun `the same post with a share tail is refused`() {
        assertEquals(1, enqueue("https://x.com/AvLoveVI/status/2115818955931861035"))
        assertEquals(-1, enqueue("https://x.com/AvLoveVI/status/2115818955931861035?s=09"))
        assertEquals("still one row", 1, DownloadQueue.size(ctx))
        assertTrue(DownloadQueue.has(ctx, "https://x.com/AvLoveVI/status/2115818955931861035?s=09"))
    }

    @Test
    fun `twitter host and i web form are refused too`() {
        enqueue("https://x.com/AvLoveVI/status/2115818955931861035")
        assertEquals(-1, enqueue("http://twitter.com/AvLoveVI/status/2115818955931861035"))
        assertEquals(-1, enqueue("https://x.com/i/web/status/2115818955931861035"))
        assertEquals(1, DownloadQueue.size(ctx))
    }

    @Test
    fun `a different post is still accepted`() {
        enqueue("https://x.com/AvLoveVI/status/2115818955931861035")
        assertEquals(2, enqueue("https://x.com/AvLoveVI/status/2118932099168383717"))
    }

    @Test
    fun `a trailing slash is the same page`() {
        enqueue("https://porneec.com/some-page")
        assertEquals(-1, enqueue("https://porneec.com/some-page/"))
        assertFalse(DownloadQueue.size(ctx) > 1)
    }
}

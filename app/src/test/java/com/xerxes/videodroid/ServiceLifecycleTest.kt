package com.xerxes.videodroid

import android.app.Application
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
 * The stuck-notification bug: the foreground service asked `DownloadQueue.size()` whether any
 * work was left, and that counts **failed and paused** rows. With nothing running but a queue
 * full of dead rows it never left the foreground, so the shade kept a live card — frozen on
 * "Cancelling…" after a Cancel — while the app itself showed nothing queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceLifecycleTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun opts() = FormatOptions(dlQuality = "720p", exportHeight = 1080, aspect = "original")

    private fun enqueue(url: String) {
        DownloadQueue.enqueue(ctx, QueuedJob(url = url, opts = opts()))
    }

    @Before
    fun clean() = DownloadQueue.clear(ctx)

    @Test
    fun `an empty queue stops the service`() {
        assertTrue(ServiceLifecycle.shouldStop(inFlight = 0, waiting = 0))
    }

    @Test
    fun `a waiting row keeps it alive`() {
        assertFalse(ServiceLifecycle.shouldStop(inFlight = 0, waiting = 1))
    }

    @Test
    fun `a claimed row keeps it alive`() {
        assertFalse(ServiceLifecycle.shouldStop(inFlight = 1, waiting = 0))
    }

    /** The actual regression: dead rows must not read as work. */
    @Test
    fun `failed and paused rows are not work`() {
        enqueue("https://x.com/u/status/2115818955931861035")
        enqueue("https://example.com/other")
        val rows = DownloadQueue.all(ctx)
        DownloadQueue.markFailed(ctx, rows[0].id, "Need X login")
        DownloadQueue.pause(ctx, rows[1].id)

        assertEquals("no row can run by itself", 0, DownloadQueue.waiting(ctx))
        assertEquals("but both rows are still in the queue", 2, DownloadQueue.size(ctx))
        assertTrue(
            "a queue of dead rows must let the service stop",
            ServiceLifecycle.shouldStop(0, DownloadQueue.waiting(ctx)),
        )
    }

    @Test
    fun `cancelling parks the remaining rows so nothing restarts by itself`() {
        enqueue("https://example.com/a")
        enqueue("https://example.com/b")

        val parked = DownloadQueue.pauseAll(ctx)

        assertEquals(2, parked.size)
        assertEquals(0, DownloadQueue.waiting(ctx))
        assertTrue(ServiceLifecycle.shouldStop(0, DownloadQueue.waiting(ctx)))
        assertEquals(2, DownloadQueue.all(ctx).count { it.paused })
    }

    @Test
    fun `the stop message says what actually happened`() {
        assertEquals("Stopped · 3 paused in the queue", ServiceLifecycle.stopMessage(3))
        assertEquals("Stopped", ServiceLifecycle.stopMessage(0))
    }
}

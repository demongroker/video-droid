package com.xerxes.videodroid

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for OPT-VIDEODROID-COMPREHENSIVE-AUDIT-001 (P0-1).
 *
 * `attempts` / `retryAfter` were written to the in-memory job but never to
 * `download_queue.json`, so every load reset them to 0. The retry cap could never be
 * reached and a retryable failure re-looped every 30 s for as long as the app lived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueRetryStateTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun opts() = FormatOptions(dlQuality = "720p", exportHeight = 1080, aspect = "original")

    private fun enqueueOne(url: String): String {
        DownloadQueue.clear(ctx)
        DownloadQueue.enqueue(ctx, QueuedJob(url = url, opts = opts()))
        return DownloadQueue.all(ctx).single().id
    }

    @Test
    fun `attempts and retryAfter survive a reload`() {
        val id = enqueueOne("https://example.com/network-fail")

        DownloadQueue.markFailure(ctx, id, "No network")

        val reloaded = DownloadQueue.all(ctx).single()
        assertEquals("attempts must be persisted", 1, reloaded.attempts)
        assertTrue(
            "retryAfter must be persisted, otherwise the backoff resets on every read",
            reloaded.retryAfter > System.currentTimeMillis(),
        )
        assertFalse("a network failure is retried, not parked as failed", reloaded.failed)
    }

    /** MAX_ATTEMPTS is 3: the fourth failure parks the row instead of looping forever. */
    @Test
    fun `the retry cap is actually reached`() {
        val id = enqueueOne("https://example.com/always-offline")

        repeat(4) { DownloadQueue.markFailure(ctx, id, "No network") }

        val reloaded = DownloadQueue.all(ctx).single()
        assertEquals(4, reloaded.attempts)
        assertTrue("after the cap the row must be parked as failed", reloaded.failed)
    }

    /** A user cancellation is not a failure and must not touch attempts. */
    @Test
    fun `releasing a claim leaves the row untouched`() {
        val id = enqueueOne("https://example.com/cancelled")
        DownloadQueue.claimNext(ctx)

        DownloadQueue.releaseClaim(id)

        val reloaded = DownloadQueue.get(ctx, id)
        assertTrue("row must still be there", reloaded != null)
        assertEquals(0, reloaded!!.attempts)
        assertFalse(reloaded.failed)
        assertFalse("the claim must be released so the row is eligible again", DownloadQueue.isClaimed(id))
    }
}

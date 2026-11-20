package com.xerxes.videodroid

import com.xerxes.videodroid.ui.QueueCopy
import com.xerxes.videodroid.ui.QueueTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue row's wording and actions are a contract with the field logs, so they are pinned
 * here rather than left inside a layout binder.
 */
class QueueCopyTest {

    private fun state(
        failed: Boolean = false,
        failReason: String = "",
        url: String = "https://example.com/v",
        paused: Boolean = false,
        running: Boolean = false,
        online: Boolean = true,
        progress: Int = 0,
    ) = QueueCopy.state(failed, failReason, url, paused, running, online, progress)

    @Test
    fun failureReasonLivesInTheStateLineNotTheTitle() {
        val s = state(failed = true, failReason = "HTTP 403")
        assertTrue(s.text.startsWith("Failed — "))
        assertEquals(QueueTone.BAD, s.tone)
        // the title is the real name, never the error
        assertEquals("[Studio] Some Scene", QueueCopy.title("[Studio] Some Scene", "https://x"))
    }

    @Test
    fun blankFailureReasonStillSaysSomethingUseful() {
        val s = state(failed = true, failReason = "")
        assertTrue(s.text.startsWith("Failed — "))
        assertTrue(s.text.length > "Failed — ".length)
    }

    @Test
    fun pausedKeepsItsProgress() {
        assertEquals("Paused · 40%", state(paused = true, progress = 40).text)
        assertEquals("Paused", state(paused = true, progress = 0).text)
        assertEquals(QueueTone.WARN, state(paused = true).tone)
    }

    @Test
    fun runningShowsPercentOrEllipsis() {
        assertEquals("Downloading 12%", state(running = true, progress = 12).text)
        assertEquals("Downloading…", state(running = true, progress = 0).text)
        assertEquals(QueueTone.BUSY, state(running = true).tone)
    }

    @Test
    fun offlineWordingIsExact() {
        val s = state(online = false)
        assertEquals("Waiting for connection", s.text)
        assertFalse(s.text.contains("resumes automatically"))
    }

    @Test
    fun queuedIsTheFallback() {
        assertEquals("Queued · 7%", state(progress = 7).text)
        assertEquals("Queued", state().text)
        assertEquals(QueueTone.NEUTRAL, state().tone)
    }

    @Test
    fun failedOutranksPausedAndRunning() {
        val s = state(failed = true, failReason = "boom", paused = true, running = true, progress = 90)
        assertTrue(s.text.startsWith("Failed — "))
    }

    @Test
    fun detailCarriesQualityAndSpeedNeverTheState() {
        assertEquals("1080p · 2.1 MB/s · 00:42", QueueCopy.detail("1080p", "2.1 MB/s · 00:42"))
        assertEquals("720p", QueueCopy.detail("720p", ""))
        assertEquals("", QueueCopy.detail("", ""))
    }

    @Test
    fun failedRowSaysRemoveAndHasNoPause() {
        assertEquals("Remove", QueueCopy.cancelLabel(failed = true))
        assertEquals("Cancel", QueueCopy.cancelLabel(failed = false))
        assertFalse(QueueCopy.showPause(failed = true))
        assertTrue(QueueCopy.showPause(failed = false))
    }

    @Test
    fun pauseLabelFollowsState() {
        assertEquals("Resume", QueueCopy.pauseLabel(paused = true))
        assertEquals("Pause", QueueCopy.pauseLabel(paused = false))
    }

    @Test
    fun retryOnlyForRetryableFailures() {
        assertFalse(QueueCopy.showRetry(failed = false, failReason = "HTTP 500"))
        assertFalse(QueueCopy.showRetry(failed = true, failReason = "no video in this tweet"))
        assertFalse(QueueCopy.showRetry(failed = true, failReason = "This account is private"))
        assertTrue(QueueCopy.showRetry(failed = true, failReason = "HTTP 503"))
    }

    @Test
    fun progressBarOnlyWhenThereIsSomethingToShow() {
        assertTrue(QueueCopy.showProgress(progress = 1, running = false))
        assertTrue(QueueCopy.showProgress(progress = 0, running = true))
        assertFalse(QueueCopy.showProgress(progress = 0, running = false))
    }

    @Test
    fun bulkPauseLabelCountsPausedRows() {
        assertEquals("Pause all", QueueCopy.bulkPauseLabel(0))
        assertEquals("Resume all (3)", QueueCopy.bulkPauseLabel(3))
    }

    @Test
    fun titleFallsBackToUrlAndTrims() {
        assertEquals("https://x/v", QueueCopy.title("", "https://x/v"))
        assertEquals("Name", QueueCopy.title("  Name  ", "https://x/v"))
    }
}

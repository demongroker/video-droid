package com.xerxes.videodroid

import com.xerxes.videodroid.ui.QueueStatusText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ghost "Working…" state: with an empty queue the screen used to keep its old status and a
 * lit "Cancel current" button. Only queue-derived lines may be cleared.
 */
class QueueStatusTextTest {

    @Test
    fun `queue lines are transient`() {
        assertTrue(QueueStatusText.isTransient("Working..."))
        assertTrue(QueueStatusText.isTransient("Queued (14)\nWorking..."))
        assertTrue(QueueStatusText.isTransient("Starting..."))
        assertTrue(QueueStatusText.isTransient("Cancelling..."))
        assertTrue(QueueStatusText.isTransient("Paused — tap Resume in the notification or the queue"))
        assertTrue(QueueStatusText.isTransient("Stopped · 3 paused in the queue"))
        assertTrue(QueueStatusText.isTransient("Downloading 42%"))
        assertTrue(QueueStatusText.isTransient("Waiting for connection… (resumes automatically)"))
    }

    @Test
    fun `results and errors are not transient`() {
        assertFalse(QueueStatusText.isTransient("Saved. Tap to open\ncontent://media/external/downloads/12"))
        assertFalse(QueueStatusText.isTransient("Need X login — Settings → Log in to X"))
        assertFalse(QueueStatusText.isTransient("Already in queue — that link is waiting.\nSee the Queue tab."))
        assertFalse(QueueStatusText.isTransient("Already in library — skipped"))
        assertFalse(QueueStatusText.isTransient("No network"))
        assertFalse(QueueStatusText.isTransient("Could not save to Downloads"))
        assertFalse(QueueStatusText.isTransient(""))
    }
}

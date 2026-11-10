package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ghost notification of 2.3.6: after a duplicate skip and a cancel the shade still said
 * "Downloading 1 · Waiting for connection…" with nothing running.
 */
class NotificationStateTest {

    @Test
    fun nothingRunningMeansNoNotificationAtAll() {
        // Skip-as-duplicate: the job never started, so the queue is empty.
        assertNull(NotificationState.text(inFlight = 0, waiting = 0, online = true))
        // Cancel mid-download, then offline: still nothing to show.
        assertNull(NotificationState.text(inFlight = 0, waiting = 0, online = false))
    }

    @Test
    fun waitingWorkIsQueuedNotDownloading() {
        assertEquals(
            "Queued · 1 download",
            NotificationState.text(inFlight = 0, waiting = 1, online = true),
        )
        assertEquals(
            "Queued · 3 downloads",
            NotificationState.text(inFlight = 0, waiting = 3, online = true),
        )
    }

    @Test
    fun onlyClaimsToWaitForTheNetworkWhenOffline() {
        assertEquals(
            "Waiting for connection… (resumes automatically)",
            NotificationState.text(inFlight = 1, waiting = 0, online = false),
        )
        assertTrue(
            NotificationState.text(inFlight = 1, waiting = 0, online = true)!!.startsWith("Downloading"),
        )
    }

    @Test
    fun countsInFlightPlusWaiting() {
        assertEquals("Downloading 2", NotificationState.text(inFlight = 2, waiting = 0, online = true))
        assertEquals("Downloading 3", NotificationState.text(inFlight = 1, waiting = 2, online = true))
    }

    @Test
    fun reconcileOnlyWhenWorkExistsWithoutAQueue() {
        assertTrue(NotificationState.needsReconcile(runnableJobs = 0, workActive = true))
        assertFalse(NotificationState.needsReconcile(runnableJobs = 2, workActive = true))
        assertFalse(NotificationState.needsReconcile(runnableJobs = 0, workActive = false))
    }
}

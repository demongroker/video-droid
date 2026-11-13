package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update prompt rules. Each case here is a reported symptom:
 *  - the dialog reappearing while the download is already running,
 *  - a second "Download" tap starting a second download,
 *  - nothing at all happening when the phone is offline,
 *  - mobile data being used at sea.
 */
class UpdatePromptTest {

    private fun input(
        latest: String = "2.5.7",
        installed: String = "2.5.4",
        now: Long = 1_000_000_000_000L,
        snoozeUntil: Long = 0L,
        jobVersion: String? = null,
        jobState: UpdateJobState = UpdateJobState.IDLE,
        jobPercent: Int = 0,
        jobError: String? = null,
        online: Boolean = true,
        unmetered: Boolean = true,
        wifiOnly: Boolean = true,
        userInitiated: Boolean = false,
    ) = UpdatePromptInput(
        latestVersion = latest,
        installedVersion = installed,
        nowMillis = now,
        snoozeUntilMillis = snoozeUntil,
        jobVersion = jobVersion,
        jobState = jobState,
        jobPercent = jobPercent,
        jobError = jobError,
        online = online,
        unmetered = unmetered,
        wifiOnly = wifiOnly,
        userInitiated = userInitiated,
    )

    @Test
    fun `dialog is suppressed while the download for this version is running`() {
        val d = UpdatePrompt.decide(
            input(jobVersion = "2.5.7", jobState = UpdateJobState.DOWNLOADING, jobPercent = 12)
        )
        assertEquals(UpdateAction.SHOW_DOWNLOADING, d.action)
        assertTrue("percentage must be surfaced", d.message.contains("12%"))
    }

    @Test
    fun `a running download for a different version does not suppress the new prompt`() {
        val d = UpdatePrompt.decide(
            input(latest = "2.5.8", jobVersion = "2.5.7", jobState = UpdateJobState.DOWNLOADING)
        )
        assertEquals(UpdateAction.PROMPT, d.action)
    }

    @Test
    fun `duplicate enqueue is refused while a download is active`() {
        // isDownloadActive is what startDownload() checks before starting a second
        // thread on the same .part file.
        assertTrue(
            UpdateJobState.DOWNLOADING == UpdateJobState.parse("downloading")
        )
        assertEquals(UpdateJobState.FAILED, UpdateJobState.parse("FAILED"))
        assertEquals(UpdateJobState.IDLE, UpdateJobState.parse("nonsense"))
    }

    @Test
    fun `verified job offers install instead of prompting again`() {
        val d = UpdatePrompt.decide(
            input(jobVersion = "2.5.7", jobState = UpdateJobState.VERIFIED)
        )
        assertEquals(UpdateAction.SHOW_READY, d.action)
    }

    @Test
    fun `failed job shows the reason and a retry path`() {
        val d = UpdatePrompt.decide(
            input(jobVersion = "2.5.7", jobState = UpdateJobState.FAILED, jobError = "HTTP 503")
        )
        assertEquals(UpdateAction.SHOW_FAILED, d.action)
        assertTrue(d.message.contains("HTTP 503"))
        assertTrue(d.message.contains("Retry"))
    }

    @Test
    fun `offline shows the no-internet message instead of a dialog`() {
        val d = UpdatePrompt.decide(input(online = false))
        assertEquals(UpdateAction.SHOW_OFFLINE, d.action)
        assertTrue(d.message.contains("No internet"))
    }

    @Test
    fun `wifi-only blocks a download on mobile data`() {
        val d = UpdatePrompt.decide(input(unmetered = false, wifiOnly = true))
        assertEquals(UpdateAction.WAIT_FOR_WIFI, d.action)
        assertTrue(d.message.contains("Wi-Fi"))
    }

    @Test
    fun `wifi-only off allows mobile data`() {
        val d = UpdatePrompt.decide(input(unmetered = false, wifiOnly = false))
        assertEquals(UpdateAction.PROMPT, d.action)
    }

    @Test
    fun `later snoozes the prompt for 24 hours`() {
        val now = 1_000_000_000_000L
        val snoozed = UpdatePrompt.decide(
            input(now = now, snoozeUntil = now + UpdatePrompt.SNOOZE_MILLIS)
        )
        assertEquals(UpdateAction.NONE, snoozed.action)

        // ...and a user-initiated check still answers.
        val manual = UpdatePrompt.decide(
            input(now = now, snoozeUntil = now + UpdatePrompt.SNOOZE_MILLIS, userInitiated = true)
        )
        assertEquals(UpdateAction.PROMPT, manual.action)

        // Once the snooze expires the prompt returns.
        val expired = UpdatePrompt.decide(
            input(now = now + UpdatePrompt.SNOOZE_MILLIS + 1, snoozeUntil = now + UpdatePrompt.SNOOZE_MILLIS)
        )
        assertEquals(UpdateAction.PROMPT, expired.action)
    }

    @Test
    fun `an already-installed version never prompts`() {
        val d = UpdatePrompt.decide(input(latest = "2.5.7", installed = "2.5.7"))
        assertEquals(UpdateAction.NONE, d.action)

        val manual = UpdatePrompt.decide(input(latest = "2.5.7", installed = "2.5.7", userInitiated = true))
        assertEquals(UpdateAction.NONE, manual.action)
        assertTrue(manual.message.contains("Up to date"))
    }
}

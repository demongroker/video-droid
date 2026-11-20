package com.xerxes.videodroid

import com.xerxes.videodroid.ui.UpdateSheetAction
import com.xerxes.videodroid.ui.UpdateSheetCopy
import com.xerxes.videodroid.ui.UpdateSheetTone
import com.xerxes.videodroid.ui.UpdateVerifyStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater sheet's rules are contracts paid for in field reports: single flight (no second
 * Download while one is running), the failure reason in the state line and never the title,
 * fail-closed refusal wording kept verbatim, and an honest bar when the total is unknown.
 */
class UpdateSheetCopyTest {

    private fun model(
        action: UpdateAction,
        message: String = "",
        percent: Int = 0,
        done: Long = 0L,
        total: Long = -1L,
        speed: Long = 0L,
        step: UpdateVerifyStep = UpdateVerifyStep.NONE,
        error: String? = null,
        wifiOnly: Boolean = true,
    ) = UpdateSheetCopy.model(
        action = action,
        decisionMessage = message,
        version = "2.7.0",
        percent = percent,
        doneBytes = done,
        totalBytes = total,
        speedBps = speed,
        step = step,
        jobError = error,
        wifiOnly = wifiOnly,
    )

    @Test
    fun downloadingShowsPercentSizeAndSpeedAndOnlyCancel() {
        val m = model(
            UpdateAction.SHOW_DOWNLOADING,
            percent = 42,
            done = 20L * 1024 * 1024,
            total = 48L * 1024 * 1024,
            speed = 2L * 1024 * 1024,
        )
        assertEquals("Update 2.7.0", m.title)
        assertEquals("Downloading 42%", m.line.text)
        assertEquals(UpdateSheetTone.BUSY, m.line.tone)
        assertEquals(42, m.progressPercent)
        assertEquals("20.0 MB of 48.0 MB", m.sizeText)
        assertEquals("2.0 MB/s", m.speedText)
        assertTrue(m.showCancel)
        // Single flight: while a download runs there is NO forward action to tap twice.
        assertNull(m.primary)
        assertFalse(m.showLater)
        assertFalse(m.showWifiToggle)
    }

    @Test
    fun unknownTotalIsIndeterminateNeverAnInventedPercent() {
        val m = model(UpdateAction.SHOW_DOWNLOADING, percent = 0, done = 5L * 1024 * 1024, total = -1L)
        assertNull(m.progressPercent)
        assertEquals("Downloading…", m.line.text)
        assertEquals("5.0 MB", m.sizeText)
    }

    @Test
    fun verificationStepsAreVisibleAndIndeterminate() {
        val sig = model(UpdateAction.SHOW_DOWNLOADING, percent = 100, total = 48L * 1024 * 1024, step = UpdateVerifyStep.SIGNATURE)
        assertEquals("Verifying signature…", sig.line.text)
        assertNull(sig.progressPercent)
        assertEquals("", sig.speedText)
        assertTrue(sig.showCancel)
        assertNull(sig.primary)

        val sum = model(UpdateAction.SHOW_DOWNLOADING, percent = 100, total = 48L * 1024 * 1024, step = UpdateVerifyStep.CHECKSUM)
        assertEquals("Checking checksum…", sum.line.text)
        assertNull(sum.progressPercent)
    }

    @Test
    fun verifiedOffersInstallAndSaysSo() {
        val m = model(UpdateAction.SHOW_READY, total = 48L * 1024 * 1024)
        assertEquals("Verified — ready to install", m.line.text)
        assertEquals(UpdateSheetTone.GOOD, m.line.tone)
        assertEquals(UpdateSheetAction.INSTALL, m.primary)
        assertEquals("Install", m.primaryLabel)
        assertEquals(100, m.progressPercent)
        assertEquals("48.0 MB", m.sizeText)
        assertFalse(m.showCancel)
    }

    @Test
    fun failureReasonLivesInTheStateLineNotTheTitle() {
        val m = model(UpdateAction.SHOW_FAILED, error = "signature mismatch — refused")
        assertEquals("Update 2.7.0", m.title)
        assertFalse(m.title.contains("signature"))
        assertEquals("Failed — signature mismatch — refused", m.line.text)
        assertEquals(UpdateSheetTone.BAD, m.line.tone)
        assertEquals(UpdateSheetAction.RETRY, m.primary)
        assertTrue(m.showLater)
        assertTrue(m.showWifiToggle)
    }

    @Test
    fun failClosedWordingIsKeptVerbatim() {
        for (reason in listOf("signature mismatch — refused", "no published checksum", "checksum mismatch")) {
            val m = model(UpdateAction.SHOW_FAILED, error = reason)
            assertTrue("reason must stay readable: ${m.line.text}", m.line.text.contains(reason))
        }
    }

    @Test
    fun blankFailureStillSaysSomethingUseful() {
        val m = model(UpdateAction.SHOW_FAILED, error = null)
        assertEquals("Failed — unknown error", m.line.text)
    }

    @Test
    fun promptOffersDownloadLaterAndWifiToggle() {
        val m = model(
            UpdateAction.PROMPT,
            message = "Version 2.7.0 is available. You have 2.6.0.",
            wifiOnly = true,
        )
        assertEquals(UpdateSheetAction.DOWNLOAD, m.primary)
        assertEquals("Download", m.primaryLabel)
        assertTrue(m.showLater)
        assertTrue(m.showWifiToggle)
        assertTrue(m.wifiOnly)
        assertFalse(m.showCancel)
        assertEquals("Version 2.7.0 is available. You have 2.6.0.", m.line.text)
    }

    @Test
    fun waitForWifiHasNoDownloadUntilTheSettingChanges() {
        val m = model(
            UpdateAction.WAIT_FOR_WIFI,
            message = "Wi-Fi only is on — update 2.7.0 will download when you're on Wi-Fi",
        )
        assertNull(m.primary)
        assertEquals(UpdateSheetTone.WARN, m.line.tone)
        assertTrue(m.showWifiToggle)
        assertTrue(m.showLater)
    }

    @Test
    fun offlineIsAMessageWithAWayOut() {
        val m = model(UpdateAction.SHOW_OFFLINE, message = "No internet — update will retry when you're back online")
        assertNull(m.primary)
        assertEquals(UpdateSheetTone.WARN, m.line.tone)
        assertTrue(m.showLater)
        assertFalse(m.showProgress)
    }

    @Test
    fun byteAndSpeedFormatting() {
        assertEquals("", UpdateSheetCopy.bytes(0))
        assertEquals("512 KB", UpdateSheetCopy.bytes(512L * 1024))
        assertEquals("1.5 MB", UpdateSheetCopy.bytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.2 GB", UpdateSheetCopy.bytes((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("", UpdateSheetCopy.speed(0))
        assertEquals("2.0 MB/s", UpdateSheetCopy.speed(2L * 1024 * 1024))
        assertEquals("", UpdateSheetCopy.sizeLine(0, -1))
        assertEquals("1.0 MB of 48.0 MB", UpdateSheetCopy.sizeLine(1024L * 1024, 48L * 1024 * 1024))
        assertEquals("5.0 MB", UpdateSheetCopy.sizeLine(5L * 1024 * 1024, -1))
    }

    @Test
    fun verifyStepParseFallsBackToNone() {
        assertEquals(UpdateVerifyStep.NONE, UpdateVerifyStep.parse(null))
        assertEquals(UpdateVerifyStep.NONE, UpdateVerifyStep.parse("junk"))
        assertEquals(UpdateVerifyStep.SIGNATURE, UpdateVerifyStep.parse("signature"))
        assertEquals(UpdateVerifyStep.CHECKSUM, UpdateVerifyStep.parse("CHECKSUM"))
    }
}
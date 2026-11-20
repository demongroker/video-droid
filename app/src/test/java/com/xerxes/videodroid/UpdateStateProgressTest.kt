package com.xerxes.videodroid

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.xerxes.videodroid.ui.UpdateVerifyStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The updater's persisted job state is what a relaunch (or the progress sheet opening
 * mid-transfer) reads instead of guessing. Pin the round-trip, including the byte/speed/step
 * fields the sheet renders, and that the original percent-only call still works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateStateProgressTest {

    private val ctx: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun beginDownloadResetsEverything() {
        UpdateState.progress(ctx, 90, 900L, 1000L, 500L)
        UpdateState.verifyStep(ctx, UpdateVerifyStep.CHECKSUM)
        UpdateState.beginDownload(ctx, "2.7.0")
        assertEquals("2.7.0", UpdateState.jobVersion(ctx))
        assertEquals(UpdateJobState.DOWNLOADING, UpdateState.jobState(ctx))
        assertEquals(0, UpdateState.jobPercent(ctx))
        assertEquals(0L, UpdateState.jobBytesDone(ctx))
        assertEquals(-1L, UpdateState.jobBytesTotal(ctx))
        assertEquals(0L, UpdateState.jobSpeedBps(ctx))
        assertEquals(UpdateVerifyStep.NONE, UpdateState.jobVerifyStep(ctx))
        assertNull(UpdateState.jobError(ctx))
    }

    @Test
    fun progressRoundTripsBytesAndSpeed() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.progress(ctx, 42, 20_000_000L, 48_000_000L, 2_000_000L)
        assertEquals(42, UpdateState.jobPercent(ctx))
        assertEquals(20_000_000L, UpdateState.jobBytesDone(ctx))
        assertEquals(48_000_000L, UpdateState.jobBytesTotal(ctx))
        assertEquals(2_000_000L, UpdateState.jobSpeedBps(ctx))
    }

    @Test
    fun percentOnlyCallKeepsWorkingAndLeavesBytesAlone() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.progress(ctx, 10, 100L, 1000L, 50L)
        UpdateState.progress(ctx, 20)
        assertEquals(20, UpdateState.jobPercent(ctx))
        assertEquals(100L, UpdateState.jobBytesDone(ctx))
        assertEquals(1000L, UpdateState.jobBytesTotal(ctx))
        assertEquals(50L, UpdateState.jobSpeedBps(ctx))
    }

    @Test
    fun percentIsClampedToAnHonestRange() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.progress(ctx, 250)
        assertEquals(100, UpdateState.jobPercent(ctx))
        UpdateState.progress(ctx, -3)
        assertEquals(0, UpdateState.jobPercent(ctx))
    }

    @Test
    fun verifyStepRoundTripsAndFallsBack() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.verifyStep(ctx, UpdateVerifyStep.SIGNATURE)
        assertEquals(UpdateVerifyStep.SIGNATURE, UpdateState.jobVerifyStep(ctx))
        UpdateState.verifyStep(ctx, UpdateVerifyStep.CHECKSUM)
        assertEquals(UpdateVerifyStep.CHECKSUM, UpdateState.jobVerifyStep(ctx))
    }

    @Test
    fun markVerifiedClearsSpeedAndStepAndError() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.verifyStep(ctx, UpdateVerifyStep.CHECKSUM)
        UpdateState.progress(ctx, 100, 48_000_000L, 48_000_000L, 2_000_000L)
        UpdateState.markVerified(ctx, "2.7.0")
        assertEquals(UpdateJobState.VERIFIED, UpdateState.jobState(ctx))
        assertEquals(100, UpdateState.jobPercent(ctx))
        assertEquals(0L, UpdateState.jobSpeedBps(ctx))
        assertEquals(UpdateVerifyStep.NONE, UpdateState.jobVerifyStep(ctx))
        // The size stays readable on the ready sheet.
        assertEquals(48_000_000L, UpdateState.jobBytesTotal(ctx))
    }

    @Test
    fun failedKeepsTheReasonForTheRetrySheet() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.markFailed(ctx, "2.7.0", "signature mismatch — refused")
        assertEquals(UpdateJobState.FAILED, UpdateState.jobState(ctx))
        assertEquals("signature mismatch — refused", UpdateState.jobError(ctx))
    }

    @Test
    fun resetClearsTheJobButNotTheSnoozeOrWifiSetting() {
        UpdateState.beginDownload(ctx, "2.7.0")
        UpdateState.progress(ctx, 42, 20L, 48L, 2L)
        UpdateState.setWifiOnly(ctx, false)
        UpdateState.reset(ctx)
        assertNull(UpdateState.jobVersion(ctx))
        assertEquals(UpdateJobState.IDLE, UpdateState.jobState(ctx))
        assertEquals(0, UpdateState.jobPercent(ctx))
        assertEquals(0L, UpdateState.jobBytesDone(ctx))
        assertEquals(-1L, UpdateState.jobBytesTotal(ctx))
        assertEquals(UpdateVerifyStep.NONE, UpdateState.jobVerifyStep(ctx))
        assertEquals(false, UpdateState.wifiOnly(ctx))
    }
}
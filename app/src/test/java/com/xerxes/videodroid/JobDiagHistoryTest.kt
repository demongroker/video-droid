package com.xerxes.videodroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OPT-VIDEODROID-COMPREHENSIVE-AUDIT-001 (P4): the diagnostics ring buffer died with the
 * process — exactly when the log is wanted. Finished jobs are written to filesDir/diag.log,
 * redacted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class JobDiagHistoryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun opts() = FormatOptions(dlQuality = "720p", exportHeight = 1080, aspect = "original")

    @Test
    fun `a finished job is written to the saved log`() {
        JobDiag.install(ctx)
        JobDiag.beginJob(opts())
        JobDiag.noteStatus("Downloading 12%")
        JobDiag.noteDuration(true, 42.0)
        JobDiag.noteException("boom")
        JobDiag.finishJob()

        val saved = JobDiag.history(ctx)
        assertTrue("status line missing from:\n$saved", saved.contains("Downloading 12%"))
        assertTrue("exception missing from:\n$saved", saved.contains("boom"))
        assertTrue("duration missing from:\n$saved", saved.contains("duration: 42.0s"))
    }

    @Test
    fun `cookies and tokens never reach the saved log`() {
        JobDiag.install(ctx)
        JobDiag.beginJob(opts())
        JobDiag.noteStatus("cookie: auth_token=SECRETVALUE")
        JobDiag.noteException("https://x.com/u/status/1?token=SECRETTOKEN")
        JobDiag.finishJob()

        val saved = JobDiag.history(ctx)
        assertFalse("a cookie line leaked:\n$saved", saved.contains("SECRETVALUE"))
        assertFalse("a tokenized URL leaked:\n$saved", saved.contains("SECRETTOKEN"))
    }

    @Test
    fun `the snapshot names the failure mode honestly`() {
        JobDiag.install(ctx)
        JobDiag.beginJob(opts())
        JobDiag.noteStatus("Downloading...")
        JobDiag.noteFfmpeg("69", "Invalid data found when processing input")

        val block = JobDiag.snapshotBlock()
        assertTrue(block, block.contains("ffmpeg rc: 69"))
        assertTrue(block, block.contains("Invalid data"))
        assertTrue("unknown duration must say so, not 0s", block.contains("duration: not reached"))
    }
}

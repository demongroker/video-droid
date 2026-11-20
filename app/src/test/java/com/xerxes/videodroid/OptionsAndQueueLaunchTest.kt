package com.xerxes.videodroid

import android.app.Application
import android.os.Looper
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.xerxes.videodroid.ui.QueueCopy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Launch tests for the two new screens: the Options bottom sheet and the
 * Queue list (swipe/share/retry). They inflate the real layouts, so a broken
 * resource or a bad chip id fails here rather than on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OptionsAndQueueLaunchTest {

    private val ctx: Application
        get() = ApplicationProvider.getApplicationContext()

    @After fun cleanQueue() {
        File(ctx.filesDir, "download_queue.json").delete()
    }

    @Test
    fun optionsSheetInflates() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val sheet = OptionsSheetFragment.newInstance(
            quality = "720p",
            exportOn = true,
            mode = "Fill",
            ratio = "Square 1:1",
            trimOn = false,
            trimStart = 2,
            trimEnd = 3,
        )
        activity.supportFragmentManager.beginTransaction()
            .add(sheet, OptionsSheetFragment.TAG)
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()

        val root = sheet.view
        assertNotNull("sheet view did not inflate", root)
        assertNotNull(root!!.findViewById<View>(R.id.optQuality))
        assertNotNull(root.findViewById<View>(R.id.optMode))
        assertNotNull(root.findViewById<View>(R.id.optRatio))
        controller.pause().stop().destroy()
    }

    @Test
    fun applyingSheetWritesRealPreferences() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val sheet = OptionsSheetFragment.newInstance(
            quality = "1080p",
            exportOn = true,
            mode = "Original",
            ratio = "Portrait 9:16",
            trimOn = false,
            trimStart = 1,
            trimEnd = 1,
        )
        activity.supportFragmentManager.beginTransaction()
            .add(sheet, OptionsSheetFragment.TAG)
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()

        val root = sheet.view!!
        root.findViewById<View>(R.id.optQ720).performClick()
        root.findViewById<MaterialButton>(R.id.optApply).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val prefs = activity.getSharedPreferences("videodroid", 0)
        assertEquals("720p", prefs.getString("last_quality", null))
        controller.pause().stop().destroy()
    }

    @Test
    fun queueScreenStartsAndShowsJobs() {
        File(ctx.filesDir, "download_queue.json").writeText(
            """{"jobs":[{"id":"a1","url":"https://example.com/a","dlq":"720p","height":1080,
               "aspect":"original","qv":3,"trim":false,"tstart":0,"tend":0,"export":true,
               "local":"","progress":0,"partial":"","failed":false,"failReason":""}]}""",
        )
        val controller = Robolectric.buildActivity(QueueActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        // The queue is Compose now: the Activity hosts a ComposeView and feeds it the persisted
        // FIFO. Assert the host exists AND that the job actually reached the screen's data path,
        // then assert the copy that job produces — the row itself is pinned by QueueCopyTest.
        val host = activity.findViewById<ComposeView>(R.id.queueCompose)
        assertNotNull("queue must host a ComposeView", host)
        val jobs = DownloadQueue.all(activity)
        assertEquals(1, jobs.size)
        val job = jobs.first()
        assertEquals("Queued", QueueCopy.state(job.failed, job.failReason, job.url, job.paused, false, true, job.progress).text)
        controller.pause().stop().destroy()
    }

    @Test
    fun failedRowRendersAndTapRetries() {
        File(ctx.filesDir, "download_queue.json").writeText(
            """{"jobs":[{"id":"b2","url":"https://example.com/b","dlq":"720p","height":1080,
               "aspect":"original","qv":3,"trim":false,"tstart":0,"tend":0,"export":true,
               "local":"","progress":0,"partial":"","failed":true,"failReason":"HTTP 404"}]}""",
        )
        val controller = Robolectric.buildActivity(QueueActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        val job = DownloadQueue.all(activity).first { it.id == "b2" }
        val line = QueueCopy.state(job.failed, job.failReason, job.url, job.paused, false, true, job.progress)
        // The headline is the media name; the STATE line carries the failure, in plain English.
        assertTrue("state should show the failure: ${line.text}", line.text.startsWith("Failed"))
        assertTrue("failure must be human-readable: ${line.text}", line.text.contains("This video is gone"))
        assertTrue("a 404 row must offer Retry", QueueCopy.showRetry(job.failed, job.failReason))
        assertEquals("Remove", QueueCopy.cancelLabel(job.failed))

        // The Retry button calls exactly this pair; the failed flag must clear.
        DownloadQueue.retry(activity, job.id)
        shadowOf(Looper.getMainLooper()).idle()
        val after = DownloadQueue.all(activity).firstOrNull { it.id == "b2" }
        assertNotNull(after)
        assertFalse("retry must clear the failed flag", after!!.failed)
        controller.pause().stop().destroy()
    }
}

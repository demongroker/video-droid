package com.xerxes.videodroid

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
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
    fun optionsSheetOpensFromMainActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.optionsButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()

        val sheet = activity.supportFragmentManager
            .findFragmentByTag(OptionsSheetFragment.TAG)
        assertNotNull("Options sheet did not open", sheet)
        val root = sheet!!.view
        assertNotNull(root)
        assertNotNull(root!!.findViewById<View>(R.id.optQuality))
        assertNotNull(root.findViewById<View>(R.id.optMode))
        assertNotNull(root.findViewById<View>(R.id.optRatio))
        controller.pause().stop().destroy()
    }

    @Test
    fun applyingSheetWritesRealPreferences() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.optionsButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()

        val sheet = activity.supportFragmentManager
            .findFragmentByTag(OptionsSheetFragment.TAG) as OptionsSheetFragment
        val root = sheet.view!!

        // Pick 720p, then Apply.
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

        val list = activity.findViewById<RecyclerView>(R.id.queueList)
        assertNotNull(list)
        assertEquals(1, list.adapter?.itemCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.queueEmpty).visibility)
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

        val list = activity.findViewById<RecyclerView>(R.id.queueList)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idle()

        val holder = list.findViewHolderForAdapterPosition(0)
        assertNotNull("no row bound", holder)
        val title = holder!!.itemView.findViewById<TextView>(R.id.queueRowTitle).text.toString()
        assertTrue("row should show the failure: $title", title.startsWith("Failed"))

        holder.itemView.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val job = DownloadQueue.all(activity).firstOrNull { it.id == "b2" }
        assertNotNull(job)
        assertFalse("tap must clear the failed flag", job!!.failed)
        controller.pause().stop().destroy()
    }
}

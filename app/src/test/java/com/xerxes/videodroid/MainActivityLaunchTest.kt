package com.xerxes.videodroid

import android.app.Application
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.xerxes.videodroid.ui.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launch smoke test: the app must reach its first frame without throwing.
 *
 * Written for the 2.0.0 field crash on Xiaomi/HyperOS, where the process died
 * before drawing anything. Runs the real Activity + real layout inflation, so a
 * bad resource, a bad theme attribute or an eager system call fails the build
 * instead of the owner's phone.
 *
 * 2.7.0: Download and Library are Compose now. Like the Queue launch tests, these assert the
 * ComposeView host exists AND that the persisted data actually reached the screen's data path
 * (settings snapshot / view prefs) — the row copy itself is pinned by MainCopyTest and
 * LibraryCopyTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityLaunchTest {

    private val ctx: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun mainActivityStarts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull("MainActivity did not create", controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun mainActivityStartsWithSavedData() {
        // A 1.9.5-era install: prefs and a persisted queue already on disk.
        ctx.getSharedPreferences("videodroid", 0).edit()
            .putString("last_quality", "1080p")
            .putBoolean("export_on", true)
            .putString("last_aspect_mode", "Original")
            .putString("last_aspect_ratio", "Landscape 16:9")
            .putInt("parallel_downloads", 3)
            .apply()
        java.io.File(ctx.filesDir, "download_queue.json").writeText(
            """{"jobs":[{"url":"https://example.com/v","dlq":"720p","height":1080,"aspect":"original","qv":3,"trim":true,"tstart":0,"tend":0,"export":true,"local":""}]}""",
        )

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun mainHostsComposeAndLoadsSavedSettings() {
        ctx.getSharedPreferences("videodroid", 0).edit()
            .putString("last_quality", "720p")
            .putBoolean("export_on", false)
            .putString("last_aspect_mode", "Fill")
            .putString("last_aspect_ratio", "Square 1:1")
            .putInt("trim_start_s", 2)
            .putInt("trim_end_s", 9)
            .apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val host = activity.findViewById<ComposeView>(R.id.mainCompose)
        assertNotNull("Download screen must host a ComposeView", host)

        // The saved prefs must reach the settings snapshot the screen renders and launchJob reads.
        val s = activity.settings.value
        assertEquals("720p", s.quality)
        assertFalse(s.exportOn)
        assertEquals("Fill", s.mode)
        assertEquals("Square 1:1", s.ratio)
        assertEquals(2, s.trimStart)
        assertEquals(9, s.trimEnd)
        // Export off means the job travels as "original", whatever the mode/ratio say.
        assertEquals("original", s.aspectToken())
        controller.pause().stop().destroy()
    }

    @Test
    fun libraryActivityStarts() {
        val controller = Robolectric.buildActivity(LibraryActivity::class.java).setup()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun libraryHostsComposeAndRemembersViewPrefs() {
        ctx.getSharedPreferences("videodroid", 0).edit()
            .putString("lib_sort", "size")
            .putBoolean("lib_list_mode", true)
            .apply()
        val controller = Robolectric.buildActivity(LibraryActivity::class.java).setup()
        val activity = controller.get()

        val host = activity.findViewById<ComposeView>(R.id.libraryCompose)
        assertNotNull("Library must host a ComposeView", host)
        assertEquals(LibrarySort.SIZE, activity.sortState.value)
        assertTrue(activity.listMode.value)
        controller.pause().stop().destroy()
    }
}
package com.xerxes.videodroid

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityLaunchTest {

    @Test
    fun mainActivityStarts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull("MainActivity did not create", controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun mainActivityStartsWithSavedData() {
        // A 1.9.5-era install: prefs and a persisted queue already on disk.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
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
    fun libraryActivityStarts() {
        val controller = Robolectric.buildActivity(LibraryActivity::class.java).setup()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }
}

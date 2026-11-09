package com.xerxes.videodroid

import android.app.Application
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bottom-bar desync: the highlight must follow the screen that is actually on top, and the
 * view must not restore a stale destination from instance state. LibraryActivity previously
 * only called `sync()`, so it also had an inert tab bar (taps did nothing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomNavSyncTest {

    private fun navOf(activity: android.app.Activity): BottomNavigationView =
        activity.findViewById(R.id.bottomNav)

    @Test
    fun `the download screen shows the download pill`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(R.id.tab_download, navOf(c.get()).selectedItemId)
        assertFalse("restore must not fight the screen", navOf(c.get()).isSaveEnabled)
        c.pause().stop().destroy()
    }

    @Test
    fun `the queue screen shows the queue pill`() {
        val c = Robolectric.buildActivity(QueueActivity::class.java).setup()
        assertEquals(R.id.tab_queue, navOf(c.get()).selectedItemId)
        c.pause().stop().destroy()
    }

    @Test
    fun `the library screen shows the library pill and its bar is wired`() {
        val c = Robolectric.buildActivity(LibraryActivity::class.java).setup()
        val nav = navOf(c.get())
        assertEquals(R.id.tab_library, nav.selectedItemId)
        assertFalse("restore must not fight the screen", nav.isSaveEnabled)
        c.pause().stop().destroy()
    }

    @Test
    fun `sync from a second screen moves the pill`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = c.get()
        val nav = navOf(activity)
        NavHelper.sync(activity, R.id.tab_queue)
        assertEquals(
            "sync must apply immediately, not a frame later",
            R.id.tab_queue,
            nav.selectedItemId,
        )
        c.pause().stop().destroy()
    }
}

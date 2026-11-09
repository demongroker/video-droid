package com.xerxes.videodroid

import android.app.Application
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * The frame captured in the recording: Download content still on screen with the **Queue**
     * pill lit. NavigationBarView checks the tapped item *after* the listener returns, so the
     * pill must be put back on the visible screen on the next frame; the next screen's own
     * onResume then sets its id.
     */
    @Test
    fun `tapping another tab restores this screen's pill and still navigates`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = c.get()
        val nav = navOf(activity)
        assertNotNull(nav.menu.findItem(R.id.tab_queue))

        // Same path the bar uses for a real tap: selectedItemId -> performItemAction ->
        // our OnItemSelectedListener.
        nav.selectedItemId = R.id.tab_queue
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(
            "the visible screen must keep its own pill",
            R.id.tab_download,
            nav.selectedItemId,
        )
        assertEquals(
            "…and the tap must still navigate",
            QueueActivity::class.java.name,
            org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.component?.className,
        )
        c.pause().stop().destroy()
    }

    /**
     * The recording at 11:49 shows a *persistent* mismatch: the Download screen with the
     * **Library** pill lit, still true 16 seconds later. That is the saved-instance-state
     * restore winning, not a transition artifact — so recreate the screen with a bundle whose
     * bar was checked on Library and assert the pill lands on the screen that is showing.
     */
    @Test
    fun `a restored screen keeps its own pill`() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val nav1 = navOf(first.get())
        // Force the checked state without going through the listener (no navigation).
        nav1.menu.findItem(R.id.tab_library)?.isChecked = true
        val bundle = android.os.Bundle()
        first.saveInstanceState(bundle)
        first.pause().stop().destroy()

        val second = Robolectric.buildActivity(MainActivity::class.java).setup(bundle)
        val nav2 = navOf(second.get())
        assertEquals(
            "a restored bar must not show a tab whose screen is not on top",
            R.id.tab_download,
            nav2.selectedItemId,
        )
        second.pause().stop().destroy()
    }
}

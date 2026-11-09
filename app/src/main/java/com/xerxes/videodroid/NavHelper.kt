package com.xerxes.videodroid

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * One bottom bar, three destinations.
 *
 * This app is three activities, not a Navigation graph, so the bar must never be the source
 * of truth: the highlight is derived from the screen that is actually on top (each activity
 * passes its own tab id) and the view's own saved-instance restore is switched off.
 *
 * Two bugs lived here:
 *  - `nav.post { }` deferred the highlight by a frame, so tapping a tab left the old pill
 *    showing for a moment (the reported lag);
 *  - BottomNavigationView restored the previous selection from instance state and fought the
 *    screen, which produced "Library content on screen, Download pill highlighted".
 */
object NavHelper {

    /** Idempotent: safe from onCreate and again from every onResume. */
    fun wire(activity: Activity, selected: Int) = install(activity, selected)

    /** Called from onResume so returning to a screen always re-syncs the bar. */
    fun sync(activity: Activity, selected: Int) = install(activity, selected)

    private fun install(activity: Activity, selected: Int) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return

        // Never let the framework restore a destination the user is not looking at.
        nav.isSaveEnabled = false

        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener false
            val target = when (item.itemId) {
                R.id.tab_download -> MainActivity::class.java
                R.id.tab_queue -> QueueActivity::class.java
                R.id.tab_library -> LibraryActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            // Snap the pill back to this screen at once: the next screen's onResume sets its
            // own. Without this the tapped tab stays highlighted during the transition while
            // this screen is still the visible one.
            apply(nav, selected)
            activity.startActivity(
                Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            true
        }

        select(nav, selected)
    }

    private fun select(nav: BottomNavigationView, selected: Int) {
        if (nav.menu.findItem(selected) == null) return
        // Immediately (this frame), then again after layout — BottomNavigationView positions
        // the active indicator itself and a single pre-layout set can be lost.
        apply(nav, selected)
        nav.post { apply(nav, selected) }
    }

    private fun apply(nav: BottomNavigationView, selected: Int) {
        if (nav.selectedItemId == selected) return
        // Belt and braces: `selectedItemId` goes through the presenter's item action, and it
        // does not reliably stick when the view is not laid out yet (cold start, and under
        // Robolectric). Checking the radio item first makes the state authoritative either way.
        nav.menu.findItem(selected)?.isChecked = true
        nav.selectedItemId = selected
    }
}

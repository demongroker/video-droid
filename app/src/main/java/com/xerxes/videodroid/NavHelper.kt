package com.xerxes.videodroid

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * One bottom bar, three destinations.
 *
 * The highlight is derived from the screen that is actually on top — each
 * activity passes its own tab id — instead of trusting whatever
 * BottomNavigationView restored from its saved instance state. That restore is
 * what used to leave Queue highlighted on the Download screen, with an empty
 * active-indicator pill, on a cold start.
 */
object NavHelper {

    fun wire(activity: Activity, selected: Int) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return

        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener false
            val target = when (item.itemId) {
                R.id.tab_download -> MainActivity::class.java
                R.id.tab_queue -> QueueActivity::class.java
                R.id.tab_library -> LibraryActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            activity.startActivity(
                Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            true
        }

        select(nav, selected)

    }

    /** Called from onResume so returning to a screen always re-syncs the bar. */
    fun sync(activity: Activity, selected: Int) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        select(nav, selected)
    }

    private fun select(nav: BottomNavigationView, selected: Int) {
        nav.post {
            if (nav.selectedItemId != selected) nav.selectedItemId = selected
        }
    }
}

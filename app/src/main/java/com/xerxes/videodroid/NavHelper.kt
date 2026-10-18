package com.xerxes.videodroid

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * One bottom bar, three destinations. Every screen wires the same menu so the
 * tab highlight and the back stack stay consistent.
 */
object NavHelper {

    fun wire(activity: Activity, selected: Int) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        nav.selectedItemId = selected
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
    }
}

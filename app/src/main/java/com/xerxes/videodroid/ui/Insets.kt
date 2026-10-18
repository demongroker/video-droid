package com.xerxes.videodroid.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge inset handling.
 *
 * From targetSdk 35/36 Android enforces edge-to-edge: the window is laid out
 * behind the status bar, the navigation bar and any display cutout. Without
 * padding, the title and the first row of content are drawn under the status
 * bar and the bottom controls sit under the gesture pill.
 *
 * `applyTo` pads the given root view by the system-bar + cutout insets, so
 * everything stays inside the safe area. Call it right after setContentView().
 */
object Insets {

    fun applyTo(root: View?) {
        if (root == null) return
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
}

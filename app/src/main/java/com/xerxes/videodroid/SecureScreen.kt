package com.xerxes.videodroid

import android.view.Window
import android.view.WindowManager

/**
 * Stage 5b: keep app content out of screenshots and out of the system Recents thumbnail.
 *
 * Applied to every screen that can show a poster, a title or a filename — Download, Queue,
 * Library and Settings — so the lock screen and the Recents preview are covered too. The
 * Library's own biometric prompt is system UI and is already excluded by Android.
 */
object SecureScreen {

    fun apply(window: Window) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}

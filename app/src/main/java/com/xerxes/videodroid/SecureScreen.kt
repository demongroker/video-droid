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

    fun enabled(): Boolean = BuildConfig.SECURE_SCREENS

    fun apply(window: Window) {
        if (!enabled()) return
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    /**
     * A dialog is its own window, so the activity's flag does not cover it. Call right after
     * show(): `dialog.show().also { SecureScreen.apply(it) }`.
     */
    fun apply(dialog: android.app.Dialog) {
        if (!enabled()) return
        runCatching { dialog.window?.let { apply(it) } }
    }

    /**
     * Type-agnostic variant for `x.show().also { ... }` chains, where the static type can be
     * Toast, PopupMenu or a dialog depending on the call site. Anything that is not a window or
     * a dialog is ignored.
     */
    fun secure(target: Any?) {
        if (!enabled()) return
        when (target) {
            is android.app.Dialog -> runCatching { target.window?.let { apply(it) } }
            is Window -> apply(target)
            is androidx.fragment.app.DialogFragment ->
                runCatching { target.dialog?.let { apply(it) } }
        }
    }

    fun apply(fragment: androidx.fragment.app.DialogFragment) {
        if (!enabled()) return
        fragment.dialog?.let { apply(it) }
    }
}

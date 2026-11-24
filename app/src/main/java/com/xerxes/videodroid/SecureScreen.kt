package com.xerxes.videodroid

import android.content.Context
import android.view.Window
import android.view.WindowManager

/**
 * Stage 5b: keep app content out of screenshots and out of the system Recents thumbnail.
 *
 * Applied to every screen that can show a poster, a title or a filename — Download, Queue,
 * Library and Settings — so the lock screen and the Recents preview are covered too. The
 * Library's own biometric prompt is system UI and is already excluded by Android.
 *
 * Screenshots are ALLOWED by default: the Settings switch "Allow screenshots" defaults to
 * ON, so FLAG_SECURE is cleared rather than set. Turning the switch OFF blocks screenshots
 * again. Until [refresh] runs (App.onCreate), the compile-time BuildConfig.SECURE_SCREENS
 * value is the fallback.
 */
object SecureScreen {

    const val PREFS = "videodroid"
    const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"

    @Volatile
    private var allowScreenshots: Boolean? = null

    /** Read the user's choice once per process. */
    fun refresh(context: Context) {
        allowScreenshots = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_SCREENSHOTS, true)
    }

    /** The stored choice, defaulting to allowed when nothing is stored yet. */
    fun allowScreenshots(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_SCREENSHOTS, true)

    /** Store the choice and make it effective immediately. */
    fun setAllowScreenshots(context: Context, allow: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ALLOW_SCREENSHOTS, allow)
            .apply()
        allowScreenshots = allow
    }

    /** True when FLAG_SECURE must be applied, i.e. screenshots are blocked. */
    fun enabled(): Boolean {
        val pref = allowScreenshots ?: return BuildConfig.SECURE_SCREENS
        return BuildConfig.SECURE_SCREENS && !pref
    }

    fun apply(window: Window) {
        if (enabled()) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            // A screen created before the toggle flipped may still carry the flag.
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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

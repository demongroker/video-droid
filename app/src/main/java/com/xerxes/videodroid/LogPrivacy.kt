package com.xerxes.videodroid

import android.content.Context

/**
 * 2.5.1 field fix: copied logs carried page titles, and a page title is often the name
 * of the video itself. Titles are now replaced with `[title]` unless the owner opts in
 * from Settings → "Include page titles in copied log" (default OFF).
 *
 * [title] is pure so the rule is unit tested rather than trusted.
 */
object LogPrivacy {

    const val PREFS = "videodroid"
    const val KEY_INCLUDE_TITLES = "log_include_titles"

    fun includeTitles(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_INCLUDE_TITLES, false)

    fun setIncludeTitles(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INCLUDE_TITLES, on).apply()
    }

    /** Log-safe title. Blank stays blank-ish, never a run of spaces. */
    fun title(raw: String?, include: Boolean, max: Int = 60): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "-"
        return if (include) text.take(max) else "[title]"
    }

    fun label(context: Context): String =
        if (includeTitles(context)) "Include page titles in copied log: on"
        else "Include page titles in copied log: off"
}

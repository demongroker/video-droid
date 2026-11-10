package com.xerxes.videodroid

import android.content.Context
import com.google.android.material.color.DynamicColors

/**
 * Appearance: VideoDroid's own black + violet theme, or Material You on top of it.
 *
 * Off by default so the app looks the way it was designed; when the owner turns it on, the
 * system wallpaper palette replaces the accent while the black background and the layout stay.
 */
object Appearance {

    private const val PREFS = "videodroid"
    private const val KEY_MATERIAL_YOU = "material_you"

    fun materialYouEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MATERIAL_YOU, false)

    fun setMaterialYou(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MATERIAL_YOU, on).apply()
    }

    /** Called once from Application.onCreate before any activity inflates. */
    fun apply(ctx: Context) {
        if (!materialYouEnabled(ctx)) return
        runCatching { DynamicColors.applyToActivitiesIfAvailable(ctx as android.app.Application) }
    }
}

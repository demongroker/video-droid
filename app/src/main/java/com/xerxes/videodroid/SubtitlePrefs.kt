package com.xerxes.videodroid

import android.content.Context
import java.util.Locale

/**
 * Subtitle preferences: one default for every job.
 *
 * Modes: `off` (default), `auto` (the site's own and auto-generated tracks in the preferred
 * language), `all` (every language the site offers).
 *
 * The language defaults to the device language and falls back to English, which is what the
 * owner asked for and what most sites actually ship.
 */
object SubtitlePrefs {

    private const val PREFS = "videodroid"
    private const val KEY_MODE = "subs_mode"
    private const val KEY_LANG = "subs_lang"
    const val FALLBACK_LANG = "en"

    data class Resolved(val mode: String, val language: String)

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, "off")
            ?.takeIf { it in setOf("off", "auto", "all") } ?: "off"

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, if (mode in setOf("off", "auto", "all")) mode else "off")
            .apply()
    }

    fun language(ctx: Context): String {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null)
        return normalizeLanguage(stored ?: deviceLanguage())
    }

    fun setLanguage(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, normalizeLanguage(tag))
            .apply()
    }

    fun resolve(ctx: Context): Resolved = Resolved(mode(ctx), language(ctx))

    /** "en-GB" → "en"; anything empty or malformed → FALLBACK_LANG. */
    fun normalizeLanguage(tag: String?): String {
        val raw = tag?.trim()?.replace('_', '-').orEmpty()
        if (raw.isEmpty()) return FALLBACK_LANG
        val primary = raw.substringBefore('-').lowercase(Locale.US)
        return if (primary.length in 2..3 && primary.all { it in 'a'..'z' }) primary else FALLBACK_LANG
    }

    fun deviceLanguage(): String = try {
        Locale.getDefault().language
    } catch (t: Throwable) {
        FALLBACK_LANG
    }

    /** Label for the Settings row. */
    fun label(ctx: Context): String {
        val m = mode(ctx)
        return when (m) {
            "auto" -> "Subtitles: auto (${language(ctx)})"
            "all" -> "Subtitles: all languages"
            else -> "Subtitles: off"
        }
    }
}

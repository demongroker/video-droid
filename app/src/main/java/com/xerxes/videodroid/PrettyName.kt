package com.xerxes.videodroid

import java.text.Normalizer
import java.util.Locale

/**
 * 2.5.4 field fix: saved files were named `<title>_<date>_<quality>` even when the page handed us a
 * perfectly good name. The owner's example — the page title
 *
 *     [FamilyTherapyXXX] Lola Cheeks – Foot Freak
 *
 * became an unreadable "…_2026-11-13_1080p" string. A title that already reads like a scene name is
 * used as-is (sanitised for the filesystem), and only titles that carry no usable name fall back to
 * the date + quality scheme.
 *
 * Pure Kotlin so the rule is unit tested rather than trusted.
 */
object PrettyName {

    /** Tags that mean "the extractor gave us nothing", not a real title. */
    private val JUNK = setOf("video", "untitled", "watch", "download", "unknown", "-", "n/a")

    /** Site suffixes and marketing tails that never belong in a filename. */
    private val TAILS = listOf(
        Regex("""\s*[|–—-]\s*[a-z0-9-]+\.(com|net|org|xxx|porn|sex)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[|–—-]\s*(free|watch|download|hd|full)\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\b(watch|download)\b.*$""", RegexOption.IGNORE_CASE),
    )

    private val BAD_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private val SPACES = Regex("""\s+""")

    /**
     * A filename-safe, human-readable title, or null when the input carries no usable name.
     *
     * En dash and em dash are kept because the owner's example uses one; everything the filesystem
     * rejects becomes "-", and runs of whitespace collapse.
     */
    fun clean(raw: String?, maxLen: Int = 90): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s = Normalizer.normalize(s, Normalizer.Form.NFC)
        for (t in TAILS) s = t.replace(s, "")
        s = s.replace(BAD_CHARS, "-")
        s = SPACES.replace(s, " ").trim(' ', '-', '.', '_')
        if (s.isEmpty()) return null
        if (s.lowercase(Locale.ROOT).trim() in JUNK) return null
        // A single word with no studio bracket and no dash is too vague to name a file after
        // ("Clip", "Preview"): the date+quality scheme is more useful there.
        if (!s.contains('[') && !s.contains(" – ") && !s.contains(" - ") && s.length < 12) return null
        return if (s.length > maxLen) s.take(maxLen).trimEnd(' ', '-', '–', '.') else s
    }

    /** True when [clean] would produce a name worth using instead of `<title>_<date>_<quality>`. */
    fun usable(raw: String?): Boolean = clean(raw) != null
}

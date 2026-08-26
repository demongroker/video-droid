package com.xerxes.videodroid

data class FormatOptions(
    val dlQuality: String,
    val exportHeight: Int,
    val aspect: String,
    val fps: Int = 30,
    val crf: Int = 3,
    val trimEnabled: Boolean = true,
    val trimStart: Int = 1,
    val trimEnd: Int = 1,
    val exportEnabled: Boolean = true
) {
    fun aspectLabel(): String {
        val a = aspect.trim()
        if (a.equals("original", ignoreCase = true)) return "Original"
        if (a.startsWith("crop:", ignoreCase = true)) return "Fill ${a.substringAfter(':')}"
        if (a.startsWith("pad:", ignoreCase = true)) return "Fit ${a.substringAfter(':')}"
        return a
    }

    /** e.g. `720p · Fill 4:3 · trim off` or `720p · Fill 4:3 · trim 1s/1s` */
    fun statusDetail(): String {
        val trim = if (trimEnabled) "trim ${trimStart}s/${trimEnd}s" else "trim off"
        return "$dlQuality · ${aspectLabel()} · $trim"
    }

    companion object {
        const val DEFAULT_TRIM_S = 1
        const val MAX_TRIM_S = 86400

        fun clampTrimSeconds(raw: Int?, lastGood: Int = DEFAULT_TRIM_S): Int {
            val v = raw ?: return fallback(lastGood)
            if (v < 0 || v > MAX_TRIM_S) return fallback(lastGood)
            return v
        }

        fun parseTrimSeconds(text: String?, lastGood: Int = DEFAULT_TRIM_S): Int {
            return clampTrimSeconds(text?.trim()?.toIntOrNull(), lastGood)
        }

        private fun fallback(lastGood: Int): Int {
            if (lastGood in 0..MAX_TRIM_S) return lastGood
            return DEFAULT_TRIM_S
        }
    }
}

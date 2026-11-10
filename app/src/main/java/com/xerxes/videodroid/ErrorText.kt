package com.xerxes.videodroid

/**
 * Turns a raw yt-dlp / extractor failure into something a human can act on.
 *
 * The Queue shows these; the raw text stays in the log (`JobDiag`) so nothing is lost. Kept pure
 * and case-insensitive so it can be unit tested, and deliberately conservative: an unrecognised
 * error falls through as the original message rather than being replaced with "Something failed".
 */
object ErrorText {

    /** Short, friendly line for a failed row. */
    fun human(raw: String): String {
        val m = raw.lowercase()
        return when {
            m.contains("novideointweet") || m.contains("no video in this tweet") ||
                (m.contains("tweet") && m.contains("no video")) ->
                "This post has no video"

            m.contains("needxlogin") || m.contains("login required") ||
                m.contains("requires authentication") || m.contains("sign in to confirm") ->
                "Needs an X login — Settings → Log in to X"

            m.contains("private") && m.contains("video") -> "This video is private"
            m.contains("removed") || m.contains("deleted") || m.contains("404") ->
                "This video is gone"
            m.contains("age") && m.contains("restrict") -> "Age-restricted — log in first"
            m.contains("unsupported url") || m.contains("unsupported site") ||
                m.contains("no suitable extractor") || m.contains("no extractor") ->
                "This site is not supported"
            m.contains("403") || m.contains("forbidden") -> "The site refused the download (403)"
            m.contains("429") || m.contains("too many requests") -> "Rate limited — try again later"
            m.contains("incomplete") || m.contains("connection dropped") ->
                "Connection dropped"
            m.contains("no space") || m.contains("enospc") -> "Not enough free space"
            m.contains("timeout") || m.contains("timed out") -> "Timed out"
            else -> raw.trim().ifEmpty { "Download failed" }
        }
    }

    /** True when retrying the same job could plausibly work. */
    fun retryable(raw: String): Boolean {
        val m = raw.lowercase()
        if (m.contains("no video in this tweet") || m.contains("novideointweet")) return false
        if (m.contains("removed") || m.contains("deleted")) return false
        if (m.contains("unsupported")) return false
        if (m.contains("private")) return false
        return true
    }
}

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
    fun human(raw: String, url: String? = null): String {
        val m = raw.lowercase()
        return when {
            m.contains("novideointweet") || m.contains("no video in this tweet") ||
                (m.contains("tweet") && m.contains("no video")) ->
                "This post has no video"

            m.contains("needxlogin") || m.contains("login required") ||
                m.contains("requires authentication") || m.contains("sign in to confirm") ->
                signInAdvice(raw, url)

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
            // The updater's field bug: "android.os.NetworkOnMainThreadException" shown to a human.
            m.contains("networkonmainthread") -> "Couldn't check for updates — try again"
            m.contains("unknownhost") || m.contains("unable to resolve host") ||
                m.contains("no address associated") -> "No connection — check your network"
            m.contains("connectexception") || m.contains("connection refused") ||
                m.contains("failed to connect") -> "Couldn't connect — try again"
            m.contains("ssl") || m.contains("certificate") -> "Secure connection failed"
            m.contains("timeout") || m.contains("timed out") -> "Timed out"
            else -> raw.trim().ifEmpty { "Download failed" }
        }
    }

    /**
     * Which "sign in" advice to give, decided by the source host.
     *
     * X and YouTube both surface a sign-in error and they need opposite instructions: an X login
     * fixes X, and nothing on this phone fixes YouTube's automated-traffic check. Reuses the same
     * classifiers FormatWorker uses so the Queue and the job error can never disagree.
     */
    private fun signInAdvice(raw: String, url: String?): String {
        val host = FormatWorker.hostOf(url)
        return when {
            FormatWorker.isXHost(host) -> "Needs an X login — Settings → Log in to X"
            FormatWorker.isYouTubeHost(host) ->
                "YouTube is asking for a sign-in — the network looks automated. Try again later."
            // No usable URL, but the extractor itself named X: that error can only come from X,
            // so keep the X advice instead of falling back to the generic line.
            raw.lowercase().contains("needxlogin") -> "Needs an X login — Settings → Log in to X"
            else -> "Site needs sign-in."
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

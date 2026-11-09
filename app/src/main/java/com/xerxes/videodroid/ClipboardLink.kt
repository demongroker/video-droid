package com.xerxes.videodroid

/**
 * Stage 2: decide whether a clipboard string is worth offering as "Download this?".
 *
 * Deliberately cheap and side-effect free so it can be unit tested — the real extraction
 * work stays in [MediaUrlParser] and in the Python downloader.
 */
object ClipboardLink {

    /**
     * True when [url] is an http(s) link that actually points somewhere: a dotted host and a
     * non-root path. This rejects bare hosts ("https://x.com/"), search strings, and the
     * "no clipboard content" placeholder the share sheet sometimes leaves behind.
     */
    fun looksDownloadable(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val u = java.net.URI(trimmed)
            val scheme = u.scheme?.lowercase()
            val host = u.host ?: return false
            (scheme == "http" || scheme == "https") &&
                host.contains(".") &&
                !u.path.isNullOrBlank() &&
                u.path != "/"
        } catch (t: Throwable) {
            false
        }
    }
}

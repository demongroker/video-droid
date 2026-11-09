package com.xerxes.videodroid

/**
 * Stable identity for one piece of media.
 *
 * Two problems this solves, both reported by the owner as "duplicate videos in the Library":
 *
 * 1. X share links carry tracking tails (`?s=09`), and the same post arrives as
 *    `x.com/user/status/123`, `twitter.com/user/status/123` or `x.com/i/web/status/123`.
 *    Compared as raw strings none of those are equal, so the queue accepted the same video
 *    twice.
 * 2. When the extractor returns no useful title (a direct MP4 resolved by the page fallback
 *    has none, and the FxTwitter path returns bare tweet text), the file was named
 *    `video_720p_Original.mp4` — so the *next* one collided and was written again with a
 *    timestamp suffix, which is a duplicate file with a different name.
 *
 * Pure Kotlin — no Android imports — so it is unit tested on the JVM.
 */
object MediaIdentity {

    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    private val STATUS = Regex("""/(?:status|statuses|i/web/status)/(\d{5,})""")

    /** Query keys that identify the share, not the media. */
    private val TRACKING = setOf(
        "s", "t", "si", "igsh", "igshid", "ref", "ref_src", "ref_url", "feature",
        "share", "share_id", "fbclid", "gclid", "mkt_tok", "trk", "src", "utm_source",
    )

    private val TWITTER_HOSTS = setOf(
        "x.com", "twitter.com", "mobile.twitter.com", "mobile.x.com",
        "vxtwitter.com", "fxtwitter.com",
    )

    /**
     * Canonical form of a pasted link: scheme-less and case-folded, tracking parameters
     * dropped, remaining query keys sorted, trailing slash removed. Null when there is no
     * usable URL in the text.
     */
    fun canonicalUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val found = URL_IN_TEXT.find(text)?.value ?: text
        val withScheme = if (found.startsWith("http", ignoreCase = true)) found else "https://$found"
        return try {
            val u = java.net.URI(withScheme)
            val host = (u.host ?: return null).lowercase()
                .removePrefix("www.")
                .let { if (it in TWITTER_HOSTS) "x.com" else it }
            if (host.isEmpty()) return null
            var path = (u.path ?: "").trimEnd('/')
            if (path.isEmpty()) path = "/"
            val keep = (u.query ?: "").split('&')
                .filter { it.isNotBlank() }
                .filterNot { TRACKING.contains(it.substringBefore('=').lowercase()) }
                .sorted()
            val q = if (keep.isEmpty()) "" else "?" + keep.joinToString("&")
            "$host$path$q".lowercase()
        } catch (_: Throwable) {
            null
        }
    }

    /** X/Twitter status id behind any of the share-link forms, or null. */
    fun twitterStatusId(raw: String?): String? {
        val c = canonicalUrl(raw) ?: return null
        if (c.substringBefore('/') != "x.com") return null
        return STATUS.find("/" + c.substringAfter('/'))?.groupValues?.get(1)
    }

    /** Last six digits of a status id — the fragment the file name carries. */
    fun statusTag(statusId: String?): String? =
        statusId?.takeLast(6)?.takeIf { it.length == 6 }

    /** True when both links point at the same media. */
    fun sameMedia(a: String?, b: String?): Boolean {
        val ca = canonicalUrl(a) ?: return false
        val cb = canonicalUrl(b) ?: return false
        if (ca == cb) return true
        val ia = twitterStatusId(a) ?: return false
        return ia == twitterStatusId(b)
    }

    /** Short stable tag for a link, used when the extractor gives no usable title. */
    fun shortTag(raw: String?): String {
        val key = canonicalUrl(raw) ?: raw.orEmpty()
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return d.take(4).joinToString("") { "%02x".format(it) }
    }
}

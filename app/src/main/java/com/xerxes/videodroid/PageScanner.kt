package com.xerxes.videodroid

/**
 * Generic "scan this page for videos" parser.
 *
 * Deliberately site-agnostic: the repo never names a site, and nothing here tries to defeat
 * DRM, logins or paywalls. It reads what a browser would already receive — tags, meta tags,
 * JSON-LD and plain URLs — and hands the candidates to [CandidatePicker] for ranking.
 *
 * No Android imports, so every rule is unit tested against hand-written fixtures.
 */
object PageScanner {

    enum class Kind { MP4, WEBM, HLS, DASH, IFRAME, UNKNOWN }

    data class Candidate(
        val url: String,
        val kind: Kind,
        val poster: String = "",
        val title: String = "",
        /** Size in bytes when the page told us (JSON-LD contentSize / meta). -1 otherwise. */
        val sizeBytes: Long = -1L,
        /** Duration in seconds when the page told us. -1 otherwise. */
        val durationSeconds: Double = -1.0,
        /** True when the stream looks encrypted — never offered for download. */
        val drm: Boolean = false,
        val sameHostAsPage: Boolean = false,
    )

    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val MEDIA_EXT = Regex("""\.(mp4|webm|m3u8|mpd|m4v)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val DRM_HINTS = listOf("widevine", "playready", "fairplay", "clearkey", "#ext-x-key", "encrypted", "drm")
    private val JUNK_HOSTS = listOf(
        "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
        "adservice", "adsystem", "adnxs", "taboola", "outbrain", "popads", "exoclick",
        "juicyads", "trafficjunky", "analytics", "beacon", "scorecardresearch",
    )

    fun scan(html: String, pageUrl: String): List<Candidate> {
        if (html.isBlank()) return emptyList()
        val pageHost = hostOf(pageUrl)
        val found = LinkedHashMap<String, Candidate>()

        fun add(raw: String, kind: Kind, poster: String = "", title: String = "", enc: Boolean = false) {
            val url = absolutize(raw, pageUrl) ?: return
            if (isJunk(url)) return
            val absPoster = if (poster.isBlank()) "" else (absolutize(poster, pageUrl) ?: poster)
            val realKind = if (kind == Kind.UNKNOWN) kindOf(url) else kind
            val drm = enc || looksEncrypted(url) || htmlNear(html, url).any { s -> DRM_HINTS.any { s.contains(it) } }
            val existing = found[url]
            found[url] = Candidate(
                url = url,
                kind = realKind,
                poster = existing?.poster?.ifBlank { absPoster } ?: absPoster,
                title = existing?.title?.ifBlank { title } ?: title,
                sizeBytes = existing?.sizeBytes ?: -1L,
                durationSeconds = existing?.durationSeconds ?: -1.0,
                drm = drm,
                sameHostAsPage = hostOf(url) == pageHost && pageHost.isNotEmpty(),
            )
        }

        // 1. <video src> / <video poster> / <source src>, including data-src lazyload attributes
        Regex("""<video\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val poster = attr(tag, "poster").orEmpty()
            for (a in listOf("src", "data-src", "data-video")) {
                attr(tag, a)?.let { add(it, kindOf(it), poster) }
            }
        }
        Regex("""<source\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            for (a in listOf("src", "data-src")) {
                attr(m.value, a)?.let { add(it, kindOf(it)) }
            }
        }

        // 2. og:video / twitter:player:stream
        Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val key = (attr(tag, "property") ?: attr(tag, "name") ?: "").lowercase()
            if (key == "og:video" || key == "og:video:url" || key == "og:video:secure_url" ||
                key == "twitter:player:stream"
            ) {
                attr(tag, "content")?.let { add(it, kindOf(it), posterOf(html)) }
            }
        }

        // 3. JSON-LD VideoObject: contentUrl/embedUrl + duration/contentSize when present
        Regex("""<script\b[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).forEach { block ->
            val json = block.groupValues[1]
            val duration = parseIsoDuration(
                Regex(""""duration"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1),
            )
            val size = Regex(""""contentSize"\s*:\s*"?(\d+)"""").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val title = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1).orEmpty()
            for (key in listOf("contentUrl", "embedUrl")) {
                Regex(""""$key"\s*:\s*"([^"]+)"""").findAll(json).forEach { m ->
                    add(m.groupValues[1], kindOf(m.groupValues[1]), title = title)
                    // fold the JSON-LD metadata onto the entry we just added
                    val url = absolutize(m.groupValues[1], pageUrl)
                    if (url != null && found.containsKey(url)) {
                        val c = found.getValue(url)
                        found[url] = c.copy(
                            durationSeconds = if (duration > 0) duration else c.durationSeconds,
                            sizeBytes = if (size > 0) size else c.sizeBytes,
                            title = c.title.ifBlank { title },
                        )
                    }
                }
            }
        }

        // 3b. Relative media paths in quoted markup or script values, resolved against the page
        Regex("""["']([^"'\s]{1,300}\.(?:mp4|webm|m3u8|mpd|m4v)(?:\?[^"'\s]*)?)["']""",
            RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            add(m.groupValues[1], kindOf(m.groupValues[1]))
        }

        // 4. Bare media URLs in markup or inline scripts
        URL_IN_TEXT.findAll(html).forEach { m ->
            val url = m.value.trimEnd('.', ',', ')', '"', '\'')
            if (MEDIA_EXT.containsMatchIn(url)) add(url, kindOf(url))
        }

        // 5. Same-origin iframes, one level deep (players are often embedded)
        Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            for (a in listOf("data-litespeed-src", "data-src", "src")) {
                val v = attr(tag, a) ?: continue
                if (v.startsWith("about:")) continue
                val abs = absolutize(v, pageUrl) ?: continue
                if (isJunk(abs)) continue
                add(abs, Kind.IFRAME)
            }
        }

        return found.values.toList()
    }

    fun kindOf(url: String): Kind {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> Kind.HLS
            path.endsWith(".mpd") -> Kind.DASH
            path.endsWith(".webm") -> Kind.WEBM
            path.endsWith(".mp4") || path.endsWith(".m4v") -> Kind.MP4
            else -> Kind.UNKNOWN
        }
    }

    /** A stream is flagged when it is obviously encrypted. We never try to get around it. */
    fun looksEncrypted(url: String): Boolean =
        DRM_HINTS.any { url.lowercase().contains(it) }

    fun hostOf(url: String): String = try {
        val withScheme = if (url.startsWith("http", true)) url else "https://$url"
        java.net.URI(withScheme).host?.lowercase()?.removePrefix("www.").orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun isJunk(url: String): Boolean {
        val low = url.lowercase()
        return JUNK_HOSTS.any { low.contains(it) }
    }

    /** A small window of markup around a URL, used only to look for DRM markers. */
    private fun htmlNear(html: String, url: String): List<String> {
        val idx = html.indexOf(url)
        if (idx < 0) return emptyList()
        val from = (idx - 400).coerceAtLeast(0)
        val to = (idx + url.length + 400).coerceAtMost(html.length)
        return listOf(html.substring(from, to).lowercase())
    }

    private fun posterOf(html: String): String {
        val m = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).firstOrNull { tag ->
            val key = (attr(tag.value, "property") ?: attr(tag.value, "name") ?: "").lowercase()
            key == "og:image" || key == "twitter:image"
        } ?: return ""
        return attr(m.value, "content").orEmpty()
    }

    /** "PT1M30S" / "PT45S" / "PT1H2M3S" → seconds; anything else → -1. */
    fun parseIsoDuration(raw: String?): Double {
        val t = raw?.trim()?.uppercase() ?: return -1.0
        if (!t.startsWith("PT")) return -1.0
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""").find(t) ?: return -1.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 3600 + min * 60 + sec
        return if (total > 0) total else -1.0
    }

    fun absolutize(raw: String, pageUrl: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.startsWith("data:", true) || v.startsWith("blob:", true)) return null
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        if (!v.startsWith("/") && !v.startsWith("//")) return null
        return try {
            val base = java.net.URI(pageUrl)
            val resolved = base.resolve(if (v.startsWith("//")) "https:$v" else v).toString()
            resolved.takeIf { it.startsWith("http") }
        } catch (t: Throwable) {
            null
        }
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?: return null
        val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        return v.ifEmpty { null }
    }
}

package com.xerxes.videodroid

/** Everything the HTML scanner found on a page. */
data class ParsedPage(
    val media: List<String>,
    val frames: List<String>,
    val decodedPayloads: List<String>,
)

/**
 * Pure-Kotlin page scanner — **no Android imports**, so it runs in a plain JVM
 * unit test.
 *
 * Written against a real failure: on WordPress/LiteSpeed tube sites the player
 * is a lazyloaded iframe (`src="about:blank"`, real URL in
 * `data-litespeed-src`) that points at `player-x.php?q=<base64>`, and the
 * base64 blob is the only place the `<video><source>` markup exists.
 */
object MediaUrlParser {

    private val MEDIA_EXT = Regex("""\.(mp4|m3u8|mpd|webm|m4v)(\?|$|&)""", RegexOption.IGNORE_CASE)

    private val JUNK_HOSTS = listOf(
        "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
        "adservice", "adsystem", "adnxs", "taboola", "outbrain", "popads",
        "exoclick", "juicyads", "trafficjunky", "histats", "scorecardresearch",
        "analytics", "beacon",
    )

    fun parse(html: String, baseUrl: String = ""): ParsedPage {
        val media = LinkedHashSet<String>()
        val frames = LinkedHashSet<String>()
        val decoded = ArrayList<String>()
        if (html.isBlank()) return ParsedPage(emptyList(), emptyList(), emptyList())

        // 1. <video src> / <source src> / poster-adjacent attributes
        Regex("""<(?:video|source)\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            attr(tag, "src")?.let { media.add(it) }
            attr(tag, "data-src")?.let { media.add(it) }
            attr(tag, "data-video")?.let { media.add(it) }
        }

        // 2. iframes: src, data-litespeed-src, data-src (lazyload chain)
        Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            for (a in listOf("data-litespeed-src", "data-src", "data-original", "data-video", "src")) {
                val v = attr(tag, a) ?: continue
                if (v.startsWith("about:") || v.isBlank()) continue
                frames.add(v)
                if (MEDIA_EXT.containsMatchIn(v)) media.add(v)
            }
        }

        // 3. meta og:video / twitter:player
        Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val key = (attr(tag, "property") ?: attr(tag, "name") ?: "").lowercase()
            if (key.contains("og:video") || key.contains("twitter:player")) {
                attr(tag, "content")?.let { if (it.startsWith("http")) media.add(it) }
            }
        }

        // 4. JSON-LD contentUrl / embedUrl
        Regex(""""(?:contentUrl|embedUrl)"\s*:\s*"([^"]+)"""")
            .findAll(html)
            .forEach { media.add(it.groupValues[1]) }

        // 5. player setup blobs: file: "...mp4"
        Regex("""(?:file|src|contentUrl)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|mpd)[^"']*)""",
            RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { media.add(it.groupValues[1]) }

        // 6. base64 ?q= payloads (the player-x.php trick), up to two levels
        val queue = ArrayDeque(listOf(html))
        var depth = 0
        while (queue.isNotEmpty() && depth < 2) {
            val doc = queue.removeFirst()
            depth++
            Regex("""[?&]q=([^&"'\s]+)""").findAll(doc).forEach { m ->
                val payload = decodeBase64Lenient(m.groupValues[1]) ?: return@forEach
                if (payload.isBlank()) return@forEach
                decoded.add(payload.take(300))
                // The payload is usually URL-encoded markup.
                val markup = runCatching { java.net.URLDecoder.decode(payload, "UTF-8") }
                    .getOrDefault(payload)
                Regex("""(?:src|file)\s*=\s*["']([^"']+)["']""")
                    .findAll(markup)
                    .forEach { media.add(it.groupValues[1]) }
                Regex("""https?://[^"'\s<>]+""")
                    .findAll(markup)
                    .filter { MEDIA_EXT.containsMatchIn(it.value) }
                    .forEach { media.add(it.value) }
                queue.add(markup)
            }
        }

        val cleanedMedia = media
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .filter { MEDIA_EXT.containsMatchIn(it) || it.contains(".m3u8") || it.contains(".mpd") }
            .distinct()

        return ParsedPage(cleanedMedia, frames.distinct(), decoded)
    }

    /** Best candidate: master playlist first, then largest-looking file. */
    fun pickBest(candidates: List<String>): String? {
        val clean = candidates
            .filter { it.startsWith("http") }
            .filter { c -> JUNK_HOSTS.none { c.lowercase().contains(it) } }
            .distinct()
        if (clean.isEmpty()) return null
        fun rank(u: String): Int {
            val l = u.lowercase()
            return when {
                l.contains(".m3u8") -> 100
                l.contains(".mpd") -> 90
                l.contains("1080") -> 80
                l.contains("720") -> 70
                l.contains(".mp4") -> 60
                l.contains(".webm") -> 50
                else -> 10
            }
        }
        return clean.maxByOrNull { rank(it) }
    }

    /**
     * Base64 that tolerates URL-safe alphabets and missing padding, which is
     * what `player-x.php?q=` uses. Returns null when the input is not base64.
     */
    fun decodeBase64Lenient(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        s = s.replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        s += "=".repeat(pad)
        if (!s.matches(Regex("""^[A-Za-z0-9+/]+={0,2}$"""))) return null
        return try {
            // Hand-rolled so it works on API 24 (java.util.Base64 is API 26)
            // and in a plain JVM unit test.
            val text = String(decodeBase64(s), Charsets.UTF_8)
            // Reject binary noise (base64 of an image, etc.)
            if (text.any { it.code < 9 }) return null
            text
        } catch (_: Throwable) {
            null
        }
    }

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun decodeBase64(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in input) {
            if (ch == '=') break
            val v = ALPHABET.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("""\b""" + Regex.escape(name) + """\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }
}

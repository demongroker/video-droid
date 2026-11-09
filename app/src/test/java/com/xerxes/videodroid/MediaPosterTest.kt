package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 3: the queue-row thumbnail comes from these exact tags. */
class MediaPosterTest {

    @Test
    fun prefersAbsoluteOgImage() {
        val html = """
            <html><head>
            <meta property="og:image" content="https://cdn.example.com/a.jpg">
            <meta name="twitter:image" content="https://cdn.example.com/b.jpg">
            </head></html>
        """.trimIndent()
        assertEquals("https://cdn.example.com/a.jpg", MediaUrlParser.poster(html))
    }

    @Test
    fun fallsBackToTwitterImage() {
        val html = """<meta name="twitter:image" content="https://cdn.example.com/t.jpg">"""
        assertEquals("https://cdn.example.com/t.jpg", MediaUrlParser.poster(html))
    }

    @Test
    fun resolvesProtocolRelativeAndRootRelative() {
        val proto = """<meta property="og:image" content="//cdn.example.com/p.jpg">"""
        assertEquals(
            "https://cdn.example.com/p.jpg",
            MediaUrlParser.poster(proto, "https://site.example.com/watch/1"),
        )
        val root = """<meta property="og:image" content="/img/p.jpg">"""
        assertEquals(
            "https://site.example.com/img/p.jpg",
            MediaUrlParser.poster(root, "https://site.example.com/watch/1"),
        )
    }

    @Test
    fun usesVideoPosterWhenNoMeta() {
        val html = """<video poster="https://cdn.example.com/v.jpg" src="https://cdn.example.com/v.mp4"></video>"""
        assertEquals("https://cdn.example.com/v.jpg", MediaUrlParser.poster(html))
    }

    @Test
    fun rejectsDataUrisAndEmptyPages() {
        assertNull(MediaUrlParser.poster("""<meta property="og:image" content="data:image/png;base64,AAA">"""))
        assertNull(MediaUrlParser.poster(""))
        assertNull(MediaUrlParser.poster("<html><head><title>x</title></head></html>"))
    }

    @Test
    fun relativeUrlWithoutBaseIsDropped() {
        assertNull(MediaUrlParser.poster("""<meta property="og:image" content="/img/p.jpg">"""))
    }
}

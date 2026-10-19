package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the 1.9.3 failure: the player is a lazyloaded iframe and
 * the media URL only exists inside a base64 `?q=` parameter.
 */
class MediaUrlParserTest {

    private val fixture: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("litespeed_player.html")!!
            .bufferedReader().use { it.readText() }
    }

    @Test
    fun `finds media url inside base64 q payload`() {
        val parsed = MediaUrlParser.parse(fixture)
        assertTrue(
            "expected the bunny CDN mp4, got ${parsed.media}",
            parsed.media.any { it.contains("rikywohu.b-cdn.net") && it.endsWith(".mp4") },
        )
    }

    @Test
    fun `pickBest returns the mp4`() {
        val best = MediaUrlParser.pickBest(MediaUrlParser.parse(fixture).media)
        assertNotNull(best)
        assertEquals("https://rikywohu.b-cdn.net/gretgrg.mp4", best)
    }

    @Test
    fun `collects the lazyloaded iframe`() {
        val parsed = MediaUrlParser.parse(fixture)
        assertTrue(
            "expected the player-x.php frame, got ${parsed.frames}",
            parsed.frames.any { it.contains("player-x.php") },
        )
    }

    @Test
    fun `base64 decodes url-safe input without padding`() {
        // "aGVsbG8" is base64url for "hello" with the padding stripped.
        assertEquals("hello", MediaUrlParser.decodeBase64Lenient("aGVsbG8"))
    }

    @Test
    fun `plain source tag is still picked up`() {
        val html = """<video controls><source src="https://cdn.test/clip.mp4" type="video/mp4"></video>"""
        val best = MediaUrlParser.pickBest(MediaUrlParser.parse(html).media)
        assertEquals("https://cdn.test/clip.mp4", best)
    }

    @Test
    fun `prefers a stream master over a flat mp4`() {
        val html = """<video><source src="https://cdn.test/low.mp4"></video>
                      <script>var src="https://cdn.test/master.m3u8";</script>"""
        val best = MediaUrlParser.pickBest(MediaUrlParser.parse(html).media)
        assertEquals("https://cdn.test/master.m3u8", best)
    }
}

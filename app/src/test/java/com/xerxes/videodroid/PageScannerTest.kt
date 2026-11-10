package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generic page scanning, driven by hand-written fixtures only — no real site HTML is copied into
 * the repo, and the scanner itself knows no site names.
 */
class PageScannerTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private val page = "https://page.example/watch/abc"

    @Test
    fun plainVideoTag() {
        val found = PageScanner.scan(fixture("plain-video.html"), page)
        assertEquals(1, found.size)
        val c = found.first()
        assertEquals("https://page.example/media/main.mp4", c.url)
        assertEquals(PageScanner.Kind.MP4, c.kind)
        assertEquals("https://page.example/thumbs/one.jpg", c.poster)
        assertTrue("same host must be flagged", c.sameHostAsPage)
        assertFalse(c.drm)
    }

    @Test
    fun openGraphVideo() {
        val found = PageScanner.scan(fixture("og-video.html"), page)
        val c = found.first { it.url.endsWith("og-main.mp4") }
        assertEquals(PageScanner.Kind.MP4, c.kind)
        assertEquals("https://page.example/cover.jpg", c.poster)
        assertFalse("foreign CDN must not be sameHost", c.sameHostAsPage)
    }

    @Test
    fun jsonLdCarriesDurationAndSize() {
        val found = PageScanner.scan(fixture("jsonld.html"), page)
        val c = found.first { it.url.endsWith("json-main.mp4") }
        assertEquals(125.0, c.durationSeconds, 0.001)
        assertEquals(73_400_320L, c.sizeBytes)
        assertEquals("JSON-LD video", c.title)
    }

    @Test
    fun hlsInsideInlineScript() {
        val found = PageScanner.scan(fixture("hls.html"), page)
        val c = found.first { it.kind == PageScanner.Kind.HLS }
        assertEquals("https://page.example/stream/master.m3u8", c.url)
    }

    @Test
    fun sameOriginIframeIsOfferedAsAPlayer() {
        val found = PageScanner.scan(fixture("iframe.html"), page)
        val c = found.first { it.kind == PageScanner.Kind.IFRAME }
        assertEquals("https://page.example/player/embed/abc123", c.url)
    }

    @Test
    fun adClipLosesToThePageVideo() {
        val found = PageScanner.scan(fixture("ad-plus-main.html"), page)
        assertEquals(2, found.size)
        // CandidatePicker must prefer the page's own file over the foreign ad clip.
        val ranked = CandidatePicker.choose(
            found.map {
                CandidatePicker.Candidate(
                    url = it.url,
                    sizeBytes = it.sizeBytes,
                    durationSeconds = it.durationSeconds,
                    sameHostAsPage = it.sameHostAsPage,
                )
            },
        )
        assertEquals("https://page.example/media/real-main.mp4", ranked?.url)
    }

    @Test
    fun encryptedStreamsAreFlaggedNotHidden() {
        val found = PageScanner.scan(fixture("drm.html"), page)
        val c = found.first()
        assertTrue("DRM must be detected", c.drm)
    }

    @Test
    fun aPageWithNothingReportsNothing() {
        assertTrue(PageScanner.scan(fixture("no-video.html"), page).isEmpty())
        assertTrue(PageScanner.scan("", page).isEmpty())
    }

    @Test
    fun isoDurations() {
        assertEquals(45.0, PageScanner.parseIsoDuration("PT45S")!!, 0.001)
        assertEquals(90.0, PageScanner.parseIsoDuration("PT1M30S")!!, 0.001)
        assertEquals(3723.0, PageScanner.parseIsoDuration("PT1H2M3S")!!, 0.001)
        assertEquals(-1.0, PageScanner.parseIsoDuration("nonsense")!!, 0.001)
        assertEquals(-1.0, PageScanner.parseIsoDuration(null)!!, 0.001)
    }

    @Test
    fun relativeUrlsResolveAndJunkIsDropped() {
        assertEquals(
            "https://page.example/a/b.mp4",
            PageScanner.absolutize("/a/b.mp4", page),
        )
        assertEquals(
            "https://cdn.example/x.mp4",
            PageScanner.absolutize("//cdn.example/x.mp4", page),
        )
        assertEquals(null, PageScanner.absolutize("data:video/mp4;base64,AAA", page))
        assertEquals(null, PageScanner.absolutize("relative/path.mp4", page))
    }
}

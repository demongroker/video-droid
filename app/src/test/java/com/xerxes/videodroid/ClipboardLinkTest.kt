package com.xerxes.videodroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 2: the "Download this?" banner must only appear for a real, pointed link. */
class ClipboardLinkTest {

    @Test
    fun acceptsRealVideoLinks() {
        assertTrue(
            ClipboardLink.looksDownloadable(
                "https://x.com/VintageB00Bs/status/2096045546322456909?a=MRToFXO1pOw_jgFyEeQ&s=19",
            ),
        )
        assertTrue(
            ClipboardLink.looksDownloadable(
                "https://porneec.com/monstersofcock-bambi-brooks-loves-big-cocks-in-her-dimples/",
            ),
        )
        assertTrue(ClipboardLink.looksDownloadable("https://www.tiktok.com/@a/video/7300000000000000000"))
        assertTrue(ClipboardLink.looksDownloadable("http://example.com/v/123"))
    }

    @Test
    fun rejectsBareHostsAndRootPaths() {
        assertFalse(ClipboardLink.looksDownloadable("https://x.com/"))
        assertFalse(ClipboardLink.looksDownloadable("https://youtube.com"))
        assertFalse(ClipboardLink.looksDownloadable("https://example.com/"))
    }

    @Test
    fun rejectsNonHttpAndNonUrls() {
        assertFalse(ClipboardLink.looksDownloadable(""))
        assertFalse(ClipboardLink.looksDownloadable("   "))
        assertFalse(ClipboardLink.looksDownloadable("ftp://example.com/a.mp4"))
        assertFalse(ClipboardLink.looksDownloadable("just some copied text"))
        assertFalse(ClipboardLink.looksDownloadable("www.example.com/video/1"))
        assertFalse(ClipboardLink.looksDownloadable("content://media/external/video/1"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertTrue(ClipboardLink.looksDownloadable("  https://x.com/a/status/1  "))
    }
}

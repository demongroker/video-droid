package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's complaint: "I see duplicate same videos from X". Both halves of the fix are
 * pinned here — the same post in its different share-link forms has to compare equal, and a
 * download with no usable title must still get a stable, distinct file name.
 */
class MediaIdentityTest {

    private val opts = FormatOptions(dlQuality = "720p", exportHeight = 1080, aspect = "original")

    @Test
    fun `x share link forms are the same media`() {
        val share = "https://x.com/AvLoveVI/status/2115818955931861035?s=09"
        val clean = "https://x.com/AvLoveVI/status/2115818955931861035"
        val twitter = "http://twitter.com/AvLoveVI/status/2115818955931861035"
        val web = "https://x.com/i/web/status/2115818955931861035"

        assertTrue(MediaIdentity.sameMedia(share, clean))
        assertTrue(MediaIdentity.sameMedia(clean, twitter))
        assertTrue(MediaIdentity.sameMedia(clean, web))
    }

    @Test
    fun `different posts are not the same media`() {
        val a = "https://x.com/user/status/2115818955931861035"
        val b = "https://x.com/user/status/2118932099168383717"
        assertFalse(MediaIdentity.sameMedia(a, b))
    }

    @Test
    fun `canonical url drops the share tail and lowercases the host`() {
        assertEquals(
            "x.com/avlovevi/status/2115818955931861035",
            MediaIdentity.canonicalUrl("https://x.com/AvLoveVI/status/2115818955931861035?s=09"),
        )
        assertEquals(
            "porneec.com/monstersofcock-bambi-brooks",
            MediaIdentity.canonicalUrl("https://porneec.com/monstersofcock-bambi-brooks/"),
        )
    }

    @Test
    fun `canonical url tolerates a whole shared message`() {
        assertEquals(
            "x.com/u/status/123456789",
            MediaIdentity.canonicalUrl("look at this https://x.com/u/status/123456789?s=09 nice"),
        )
    }

    @Test
    fun `status tag and tag pattern come out of any link form`() {
        val id = MediaIdentity.twitterStatusId("https://twitter.com/u/status/2115818955931861035?s=09")
        assertEquals("2115818955931861035", id)
        assertEquals("861035", MediaIdentity.statusTag(id))
        assertNull(MediaIdentity.twitterStatusId("https://porneec.com/whatever/"))
        // Anchored: `_` is a LIKE wildcard, so it must be escaped or any 6 characters match.
        assertEquals("%\\_861035\\_%", MediaIdentity.tagLikePattern("861035"))
        assertNull(MediaIdentity.tagLikePattern("123"))
    }

    @Test
    fun `no title still gives a stable and distinct file name`() {
        val a = FormatWorker.fileNameBase(opts, "", "https://porneec.com/a-page/")
        val aAgain = FormatWorker.fileNameBase(opts, null, "https://porneec.com/a-page/")
        val b = FormatWorker.fileNameBase(opts, "", "https://porneec.com/b-page/")

        assertEquals("same link must always produce the same name", a, aAgain)
        assertEquals("video_${MediaIdentity.shortTag("https://porneec.com/a-page/")}_720p_Original", a)
        assertNotEquals("two different links must not share a name", a, b)
    }

    @Test
    fun `a tweet name carries its status tag`() {
        val name = FormatWorker.fileNameBase(opts, "AvLoveVI_-_some text-[2115818955931861035]")
        assertTrue("expected the status tag in $name", name.contains("861035"))
        assertTrue("expected the quality tag in $name", name.endsWith("720p_Original"))
    }

    @Test
    fun `a bare fallback title is normalised to carry the tweet id`() {
        // Regression: the FxTwitter path returns bare tweet text, the native path returns
        // "<account>_-_<text>-[<id>]". Without the id the name falls back to
        // "video_<tag>_...", which is how duplicate files appeared.
        val url = "https://x.com/user/status/2115818955931861035?s=09"
        assertEquals("2115818955931861035", MediaIdentity.twitterStatusId(url))
        assertTrue(FormatWorker.fileNameBase(opts, "some text [2115818955931861035]").contains("861035"))
    }
}

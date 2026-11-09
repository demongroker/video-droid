package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A crash log gets pasted into chats, so it must never carry a query string or a token. */
class PrivacyTest {

    @Test
    fun stripsQueryStringsButKeepsHostAndPath() {
        assertEquals(
            "https://x.com/VintageB00Bs/status/2096045546322456909",
            Privacy.stripUrls("https://x.com/VintageB00Bs/status/2096045546322456909?a=MRToF&s=19"),
        )
        assertEquals(
            "https://porneec.com/monstersofcock-bambi/",
            Privacy.stripUrls("https://porneec.com/monstersofcock-bambi/?utm_source=x&utm_medium=y"),
        )
        assertEquals(
            "https://bobirado.b-cdn.net/edfgab.mp4",
            Privacy.stripUrls("https://bobirado.b-cdn.net/edfgab.mp4#t=10"),
        )
    }

    @Test
    fun leavesPlainUrlsAlone() {
        val url = "https://example.com/a/video.mp4"
        assertEquals(url, Privacy.stripUrls(url))
    }

    @Test
    fun redactsCookieAndTokenMaterial() {
        val raw = """
            auth_token=abc123def456
            ct0=deadbeefcafe
            Cookie: auth_token=zzz; ct0=yyy
            Authorization: Bearer sk-live-999
        """.trimIndent()
        val out = Privacy.redact(raw)
        assertFalse("token value must not survive", out.contains("abc123def456"))
        assertFalse(out.contains("deadbeefcafe"))
        assertFalse(out.contains("sk-live-999"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun redactionAlsoStripsQueriesInsideLines() {
        val out = Privacy.redact("job failed for https://site/watch/1?sessionid=SECRETVALUE")
        assertFalse(out.contains("SECRETVALUE"))
        assertTrue(out.contains("https://site/watch/1"))
    }
}

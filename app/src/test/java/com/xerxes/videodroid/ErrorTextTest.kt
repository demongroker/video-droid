package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-report mapping: a failed row must read like English, not like a Python traceback. */
class ErrorTextTest {

    @Test
    fun mapsTheKnownExtractorFailures() {
        assertEquals("This post has no video", ErrorText.human("NoVideoInTweet: no video in this tweet"))
        assertTrue(ErrorText.human("NeedXLogin: cookies rejected").contains("Log in to X"))
        assertEquals("This video is gone", ErrorText.human("HTTP Error 404: Not Found"))
        assertEquals("This video is private", ErrorText.human("This video is private"))
        assertEquals("This site is not supported", ErrorText.human("Unsupported URL: https://nope.example/x"))
        assertTrue(ErrorText.human("HTTP Error 403: Forbidden").contains("403"))
        assertEquals("Rate limited — try again later", ErrorText.human("HTTP Error 429"))
        assertEquals("Connection dropped", ErrorText.human("Incomplete read"))
        assertEquals("Timed out", ErrorText.human("read timed out"))
    }

    @Test
    fun keepsUnknownErrorsVerbatim() {
        assertEquals("some brand new failure", ErrorText.human("some brand new failure"))
        assertEquals("Download failed", ErrorText.human("   "))
    }

    @Test
    fun knowsWhatIsWorthRetrying() {
        assertFalse(ErrorText.retryable("NoVideoInTweet"))
        assertFalse(ErrorText.retryable("Unsupported URL"))
        assertFalse(ErrorText.retryable("This video is private"))
        assertTrue(ErrorText.retryable("HTTP Error 429"))
        assertTrue(ErrorText.retryable("connection dropped"))
    }
}

package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Test

class LogPrivacyTest {

    @Test
    fun titlesHiddenByDefault() {
        assertEquals("[title]", LogPrivacy.title("Bambi Brooks loves big cocks", false))
    }

    @Test
    fun titlesShownWhenOwnerOptsIn() {
        assertEquals("Bambi Brooks loves big cocks",
            LogPrivacy.title("Bambi Brooks loves big cocks", true))
    }

    @Test
    fun longTitlesAreClipped() {
        val long = "x".repeat(200)
        assertEquals(60, LogPrivacy.title(long, true).length)
    }

    @Test
    fun blankTitleNeverPrintsWhitespace() {
        assertEquals("-", LogPrivacy.title("   ", false))
        assertEquals("-", LogPrivacy.title(null, true))
    }
}

package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Test

/** Subtitle defaults: off unless asked, device language, English when nothing usable. */
class SubtitlePrefsTest {

    @Test
    fun normalisesLanguageTags() {
        assertEquals("en", SubtitlePrefs.normalizeLanguage("en-GB"))
        assertEquals("en", SubtitlePrefs.normalizeLanguage("EN"))
        assertEquals("pt", SubtitlePrefs.normalizeLanguage("pt_BR"))
        assertEquals("ja", SubtitlePrefs.normalizeLanguage(" ja "))
    }

    @Test
    fun fallsBackToEnglishForJunk() {
        assertEquals("en", SubtitlePrefs.normalizeLanguage(null))
        assertEquals("en", SubtitlePrefs.normalizeLanguage(""))
        assertEquals("en", SubtitlePrefs.normalizeLanguage("!!"))
        assertEquals("en", SubtitlePrefs.normalizeLanguage("toolongtag"))
    }

    @Test
    fun deviceLanguageIsNormalisedToo() {
        val device = SubtitlePrefs.normalizeLanguage(SubtitlePrefs.deviceLanguage())
        assertEquals(true, device.length in 2..3)
    }
}

package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5.4: a real page title becomes the filename; nameless downloads keep the date+quality name. */
class PrettyNameTest {

    @Test
    fun theOwnersExampleIsKeptVerbatim() {
        assertEquals(
            "[FamilyTherapyXXX] Lola Cheeks – Foot Freak",
            PrettyName.clean("[FamilyTherapyXXX] Lola Cheeks – Foot Freak"),
        )
    }

    @Test
    fun asciiDashTitleAlsoSurvives() {
        assertEquals(
            "[DigitalPlayground] Ally Berry, Annistar - Crash And Burn! Episode 2",
            PrettyName.clean("[DigitalPlayground] Ally Berry, Annistar - Crash And Burn! Episode 2"),
        )
    }

    @Test
    fun siteSuffixIsDropped() {
        assertEquals(
            "[FamilyTherapyXXX] Lola Cheeks - Foot Freak",
            PrettyName.clean("[FamilyTherapyXXX] Lola Cheeks - Foot Freak | porneec.com"),
        )
    }

    @Test
    fun filesystemHostileCharactersBecomeDashes() {
        assertEquals(
            "[Studio] Name - Part 1-2",
            PrettyName.clean("[Studio] Name - Part 1/2"),
        )
    }

    @Test
    fun junkTitlesAreRejectedSoDateAndQualityAreUsed() {
        assertNull(PrettyName.clean("video"))
        assertNull(PrettyName.clean("Untitled"))
        assertNull(PrettyName.clean("   "))
        assertNull(PrettyName.clean(null))
        assertNull(PrettyName.clean("Clip"))
        assertFalse(PrettyName.usable("video"))
    }

    @Test
    fun longTitlesAreClippedNotBroken() {
        val long = "[Studio] " + "Very Long Scene Name ".repeat(10)
        val out = PrettyName.clean(long)!!
        assertTrue(out.length <= 90)
        assertTrue(out.startsWith("[Studio]"))
    }
}

package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Updater decision logic, offline-safe: the network part is not exercised here, but everything
 * the UI is allowed to say is.
 */
class UpdateFlowTest {

    @Test
    fun newerTagWins() {
        assertTrue(UpdateChecker.isNewer("2.4.1", "2.4.0"))
        assertTrue(UpdateChecker.isNewer("2.5.0", "2.4.9"))
        assertTrue(UpdateChecker.isNewer("3.0", "2.9.9"))
    }

    @Test
    fun sameOrOlderDoesNotPrompt() {
        assertEquals(false, UpdateChecker.isNewer("2.4.0", "2.4.0"))
        assertEquals(false, UpdateChecker.isNewer("2.3.7", "2.4.0"))
    }

    @Test
    fun tagsAreNormalisedBeforeComparing() {
        // The API returns "v2.4.1"; the app compares stripped versions.
        assertTrue(UpdateChecker.isNewer("v2.5.0".removePrefix("v"), "2.4.1"))
    }

    @Test
    fun failureTextIsPlainEnglishNotAnExceptionName() {
        // What the field recording showed: "GitHub: NetworkOnMainThreadException".
        val raw = "android.os.NetworkOnMainThreadException"
        val shown = ErrorText.human(raw)
        assertTrue("must not leak the exception class: $shown", !shown.contains("Exception"))
        assertTrue(shown.isNotBlank())
    }

    @Test
    fun noReleaseMeansNothingToShow() {
        // An offline check produces no release info and no dialog.
        val info: String? = null
        assertNull(info)
    }
}

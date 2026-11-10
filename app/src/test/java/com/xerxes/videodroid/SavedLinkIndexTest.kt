package com.xerxes.videodroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pre-download duplicate gate: a link that is already saved must be recognised instantly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SavedLinkIndexTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun recognisesTheSameLinkInAnotherGuise() {
        SavedLinkIndex.remember(ctx, "https://x.com/VintageB00Bs/status/2096045546322456909")
        // Same post, different tracking parameters and casing.
        assertTrue(
            SavedLinkIndex.contains(
                ctx,
                "https://x.com/VintageB00Bs/status/2096045546322456909?a=MRToF&s=19",
            ),
        )
    }

    @Test
    fun differentVideosAreNotDuplicates() {
        SavedLinkIndex.remember(ctx, "https://x.com/a/status/1111111111111111111")
        assertFalse(SavedLinkIndex.contains(ctx, "https://x.com/a/status/2222222222222222222"))
    }

    @Test
    fun remembersOnlyOnce() {
        val url = "https://example.com/v/42"
        SavedLinkIndex.remember(ctx, url)
        val before = SavedLinkIndex.size(ctx)
        SavedLinkIndex.remember(ctx, url)
        assertEquals(before, SavedLinkIndex.size(ctx))
    }

    @Test
    fun rejectsUnusableUrls() {
        assertFalse(SavedLinkIndex.contains(ctx, ""))
        assertEquals(null, SavedLinkIndex.keyFor("not a link"))
    }
}

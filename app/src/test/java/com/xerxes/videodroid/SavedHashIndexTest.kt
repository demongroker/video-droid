package com.xerxes.videodroid

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage 5: the saved-bytes index round-trips through disk and never invents a hit. */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.13 tops out at SDK 34, and the real App class loads Chaquopy's native
// crypto library which does not exist on the JVM — use a plain Application for the test.
@Config(sdk = [34], application = android.app.Application::class)
class SavedHashIndexTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun remembersAndFindsMediaIds() {
        val hash = "abc123"
        assertNull(SavedHashIndex.contains(ctx, hash))
        SavedHashIndex.remember(ctx, hash, 4242L)
        assertEquals(4242L, SavedHashIndex.contains(ctx, hash))
        // Survives a re-read from disk.
        assertEquals(4242L, SavedHashIndex.contains(ctx, "abc123"))
    }

    @Test
    fun ignoresBlankHashesAndBadIds() {
        SavedHashIndex.remember(ctx, null, 1L)
        SavedHashIndex.remember(ctx, "", 1L)
        SavedHashIndex.remember(ctx, "x", -1L)
        assertNull(SavedHashIndex.contains(ctx, ""))
        assertNull(SavedHashIndex.contains(ctx, null))
        assertNull(SavedHashIndex.contains(ctx, "x"))
    }
}

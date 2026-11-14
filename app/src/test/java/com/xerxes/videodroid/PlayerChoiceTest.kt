package com.xerxes.videodroid

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Play in Vela" handoff.
 *
 * On Android 11+ an app cannot see another app's package unless the manifest
 * declares it in <queries>, so velaInstalled() was always false in the field even
 * with Vela installed. These tests pin both halves: the null when Vela is not
 * visible, and the exact intent when it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerChoiceTest {

    private val uri: Uri = Uri.parse("content://com.xerxes.videodroid.fileprovider/videos/clip.mp4")

    @Test
    fun velaIntentIsNullWhenVelaIsNotVisible() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // Nothing is installed in the Robolectric sandbox, which is exactly the
        // state Android 11+ puts us in when <queries> is missing.
        assertNull(PlayerChoice.velaIntent(ctx, uri))
    }

    @Test
    fun velaIntentTargetsTheVelaPlayerWithAVideoGrant() {
        val intent = PlayerChoice.velaIntentFor(uri)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("app.vela.player", intent.`package`)
        assertEquals("video/*", intent.type)
        assertTrue(
            "must grant read access or Vela cannot open our content URI",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertEquals(uri, intent.data)
        assertNotNull("ClipData carries the grant for receivers that read the clip", intent.clipData)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun systemIntentIsNotPinnedToVela() {
        val intent = PlayerChoice.systemIntent(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertNull("the fallback must go to whatever the user chose", intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

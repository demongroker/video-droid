package com.xerxes.videodroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screenshots are ALLOWED by default: the "Allow screenshots" preference defaults to ON,
 * so FLAG_SECURE must NOT be applied until the user turns the switch off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SecureScreenTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun clearPref() {
        ctx.getSharedPreferences(SecureScreen.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun screenshotsAllowedByDefault() {
        clearPref()
        SecureScreen.refresh(ctx)
        assertTrue("default must be allowed", SecureScreen.allowScreenshots(ctx))
        assertFalse("default must not apply FLAG_SECURE", SecureScreen.enabled())
    }

    @Test
    fun turningTheSwitchOffBlocksScreenshots() {
        clearPref()
        SecureScreen.setAllowScreenshots(ctx, false)
        assertFalse(SecureScreen.allowScreenshots(ctx))
        assertTrue("opting out must re-apply FLAG_SECURE", SecureScreen.enabled())
    }

    @Test
    fun turningItBackOnClearsTheFlag() {
        SecureScreen.setAllowScreenshots(ctx, false)
        SecureScreen.setAllowScreenshots(ctx, true)
        assertFalse(SecureScreen.enabled())
    }
}

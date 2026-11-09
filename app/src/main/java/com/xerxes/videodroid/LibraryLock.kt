package com.xerxes.videodroid

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Stage 4: fingerprint / PIN gate for the Library.
 *
 * One prompt covers both: device biometrics with the device PIN, pattern or password as the
 * fallback, so a phone without an enrolled finger still opens. The switch lives in Settings.
 */
object LibraryLock {

    private const val PREFS = "videodroid"
    const val KEY_LOCK = "library_lock"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCK, on).apply()
    }

    /** True when the device can actually satisfy the prompt. */
    fun canAuthenticate(ctx: Context): Boolean {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return BiometricManager.from(ctx).canAuthenticate(allowed) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the prompt. [onSuccess] runs only on a real unlock; [onFail] on cancel or error.
     * When the device has no screen lock at all the gate cannot be enforced, so we let the
     * owner in and say so rather than locking them out of their own library.
     */
    fun authenticate(
        activity: AppCompatActivity,
        onSuccess: () -> Unit,
        onFail: () -> Unit,
        onNoCredential: () -> Unit,
    ) {
        if (!canAuthenticate(activity)) {
            onNoCredential()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFail()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Library locked")
            .setSubtitle("Unlock to see your downloads")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onFail() }
    }
}

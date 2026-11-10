package com.xerxes.videodroid

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptics for the actions that deserve a physical confirmation: queueing a download, pausing or
 * cancelling a row, starting a move/delete, and the About tap sequence.
 *
 * Silently does nothing on a device without a vibrator, and never throws — a missing motor must
 * not be able to break a download flow.
 */
object Haptics {

    /** A short, crisp tick. */
    fun tick(context: Context) = buzz(context, 12L, 90)

    /** A slightly heavier thud for destructive or irreversible actions. */
    fun thud(context: Context) = buzz(context, 24L, 160)

    private fun buzz(context: Context, ms: Long, amplitude: Int) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        }
    }
}

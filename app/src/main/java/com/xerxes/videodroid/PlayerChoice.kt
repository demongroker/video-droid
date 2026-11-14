package com.xerxes.videodroid

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Play in Vela" when the Vela player is installed, otherwise the system player.
 *
 * Deliberately optimistic about the package: if Vela is there we hand it the content URI with a
 * read grant; if it refuses, the caller falls back to the normal player.
 */
object PlayerChoice {

    const val VELA_PACKAGE = "app.vela.player"

    fun velaInstalled(ctx: Context): Boolean =
        runCatching {
            ctx.packageManager.getPackageInfo(VELA_PACKAGE, 0)
            true
        }.getOrDefault(false)

    /** Intent for Vela, or null when it is not installed. */
    fun velaIntent(ctx: Context, uri: Uri): Intent? {
        if (!velaInstalled(ctx)) return null
        return velaIntentFor(uri)
    }

    /**
     * The Vela intent itself, without the install check.
     *
     * Split out because it is the part a unit test can pin down exactly: target
     * package, action, MIME type, grant flags, and the ClipData that makes the
     * URI grant reliable for players that read the clip instead of the data URI.
     */
    fun velaIntentFor(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        setPackage(VELA_PACKAGE)
        // Android 11+ drops a bare FLAG_GRANT_READ_URI_PERMISSION for some
        // receivers; carrying the URI in ClipData makes the grant explicit.
        clipData = android.content.ClipData.newRawUri("video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun systemIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** @return true when the video was handed to some player. */
    fun play(ctx: Context, uri: Uri): Boolean {
        velaIntent(ctx, uri)?.let { vela ->
            if (runCatching { ctx.startActivity(vela) }.isSuccess) return true
        }
        return runCatching { ctx.startActivity(systemIntent(uri)) }.isSuccess
    }
}

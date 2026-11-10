package com.xerxes.videodroid

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: "Download clipboard link".
 *
 * Tapping it reads the clipboard and, if it holds a usable video link, hands it to MainActivity
 * the same way a share does — straight into the queue with the last preset. Android shows its own
 * toast on some ROMs when an app reads the clipboard from a tile, so the link is only read on an
 * explicit tap.
 */
class DownloadTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Clipboard link"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val url = clipboardLink() ?: run {
            android.widget.Toast.makeText(
                this,
                "No video link on the clipboard",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        Haptics.tick(this)
        runCatching {
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun clipboardLink(): String? = runCatching {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = cm?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        val match = Regex("""https?://\S+""").find(text)?.value ?: return null
        match.takeIf { ClipboardLink.looksDownloadable(it) }
    }.getOrNull()
}

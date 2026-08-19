package com.xerxes.videodroid

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

object AdBlock {
    private const val PREFS = "videodroid"
    const val KEY_ENABLED = "open_page_adblock"

    private val hosts = HashSet<String>()
    private val loaded = AtomicBoolean(false)

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, on)
            .apply()
    }

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded.get()) return
        try {
            ctx.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val h = raw.trim().lowercase()
                    if (h.isNotEmpty() && !h.startsWith("#")) hosts.add(h)
                }
            }
        } catch (_: Exception) {
        }
        loaded.set(true)
    }

    fun emptyOk(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    fun shouldBlock(requestUrl: String, pageHost: String?): Boolean {
        if (OpenPageActivity.looksLikeMedia(requestUrl, null)) return false
        val host = try {
            Uri.parse(requestUrl).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        val page = pageHost?.lowercase()?.removePrefix("www.")
        val h = host.removePrefix("www.")
        if (page != null && (h == page || h.endsWith(".$page"))) return false
        return hostMatches(h)
    }

    private fun hostMatches(host: String): Boolean {
        if (hosts.contains(host)) return true
        var i = host.indexOf('.')
        while (i > 0 && i < host.length - 1) {
            val suffix = host.substring(i + 1)
            if (hosts.contains(suffix)) return true
            i = host.indexOf('.', i + 1)
        }
        return false
    }
}

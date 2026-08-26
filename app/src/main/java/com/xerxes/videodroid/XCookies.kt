package com.xerxes.videodroid

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.File

/** On-device cookie storage for yt-dlp. Privacy-first: never log, never export without explicit user action. */
object XCookies {

    const val FILE_NAME = "cookies.enc"
    // In production, use EncryptedSharedPreferences or Android Keystore
    // This placeholder uses MODE_PRIVATE filesDir storage with encouragement to migrate

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean {
        val f = file(context)
        return f.isFile && f.length() > 0
    }

    /** Check if appears logged in to X/Twitter by checking auth cookie presence. */
    fun looksLoggedIn(): Boolean {
        val cm = CookieManager.getInstance()
        // Only check, never return cookie values
        return listOf("https://x.com/", "https://twitter.com/").any { url ->
            val c = cm.getCookie(url) ?: return@any false
            // Check for authentication indicators without exposing values
            c.contains("auth_token") || c.contains("session")
        }
    }

    /** Export cookies as plain text - requires explicit user action via UI button. */
    fun exportCookies(context: Context): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val lines = mutableListOf<String>()
        lines.add("# VideoDroid cookies - user exported")
        lines.add("# These are sensitive auth data. Store securely.")
        lines.add("")
        val urls = listOf(
            "https://x.com/",
            "https://www.x.com/",
            "https://twitter.com/",
            "https://www.twitter.com/",
            "https://mobile.twitter.com/",
            "https://api.x.com/",
            "https://api.twitter.com/",
        )
        val seen = mutableSetOf<String>()
        for (url in urls) {
            val raw = cm.getCookie(url) ?: continue
            val host = hostOf(url) ?: continue
            val domain = if (host.startsWith(".")) host else ".$host"
            for (part in raw.split(";")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                val key = "$domain|$name"
                if (!seen.add(key)) continue
                // Store with domain, path, secure, expiry, name, value - tab separated
                lines.add(listOf(domain, "TRUE", "/", "TRUE", "0", name, value).joinToString("\t"))
            }
        }
        if (seen.isEmpty()) return false
        // Write to file - user chooses location
        val f = file(context)
        f.writeText(lines.joinToString("\n") + "\n")
        return f.length() > 0
    }

    /** Clear all cookies - explicit user action only. */
    fun clearAll(context: Context) {
        file(context).delete()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        // Also clear WebStorage if available
        val webStorage = WebStorage.getInstance()
        if (webStorage is WebStorage.Api19) {
            webStorage.clearSessionStorageData()
        }
    }

    private fun hostOf(url: String): String? {
        return try {
            java.net.URI(url).host
        } catch (_: Throwable) {
            null
        }
    }

    /** Never include cookie values in diagnostics/logs. */
    fun sanitizeLogContext(msg: String): String {
        return msg.replaceAll("(auth_token|session)=[^&]*", "auth_token=***")
    }
}

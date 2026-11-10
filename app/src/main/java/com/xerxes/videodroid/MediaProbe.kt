package com.xerxes.videodroid

import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort size probe for scanned candidates.
 *
 * Only ever called from a background thread. A HEAD request is cheap; when the server refuses it
 * we fall back to a one-byte range GET, and when that fails too the size stays unknown (-1) and
 * [CandidatePicker] simply ranks that candidate on what it does know. Nothing here downloads a
 * body, and no cookie or token is attached.
 */
object MediaProbe {

    fun sizeOf(url: String, timeoutMs: Int = 8000): Long {
        headSize(url, timeoutMs)?.let { return it }
        return rangeSize(url, timeoutMs)
    }

    private fun headSize(url: String, timeoutMs: Int): Long? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VideoDroid")
        }
        conn.responseCode.takeIf { it in 200..299 }
            ?.let { conn.getHeaderField("Content-Length")?.toLongOrNull() }
            ?.takeIf { it > 0 }
            ?: run { conn.disconnect(); null }
            .also { runCatching { conn.disconnect() } }
    } catch (t: Throwable) {
        null
    }

    private fun rangeSize(url: String, timeoutMs: Int): Long = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VideoDroid")
        }
        val total = conn.getHeaderField("Content-Range")
            ?.substringAfterLast('/')
            ?.toLongOrNull()
        runCatching { conn.disconnect() }
        total?.takeIf { it > 0 } ?: -1L
    } catch (t: Throwable) {
        -1L
    }
}

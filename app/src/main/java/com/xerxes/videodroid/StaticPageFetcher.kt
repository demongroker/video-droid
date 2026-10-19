package com.xerxes.videodroid

import android.content.Context
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL

/** What a plain HTTP fetch of the page returned, for diagnostics. */
data class StaticFetch(
    val status: Int,
    val finalUrl: String,
    val html: String,
    val title: String,
    val blocked: String?,
    val error: String? = null,
) {
    val length: Int get() = html.length
}

/**
 * Fetches a page exactly the way a desktop browser would, without a WebView.
 *
 * This runs **before** the sniffer because the interesting markup is static:
 * a lazyloaded iframe plus a base64 `?q=` player parameter. A WebView adds
 * scroll/timing/JS variables that we cannot control on someone else's ROM.
 */
object StaticPageFetcher {

    private const val TIMEOUT_MS = 20_000

    fun fetch(context: Context, pageUrl: String): StaticFetch =
        fetchRaw(context, pageUrl, pageUrl, depth = 0)

    /**
     * @param referer page to send as Referer (players often require it)
     */
    fun fetchRaw(
        context: Context,
        url: String,
        referer: String,
        depth: Int = 0,
    ): StaticFetch {
        if (depth > 2) return StaticFetch(0, url, "", "", null, "redirect depth exceeded")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", PageSniffer.DESKTOP_UA)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                )
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", referer)
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                CookieManager.getInstance().getCookie(url)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val html = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            val finalUrl = conn.url?.toString() ?: url
            val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
            val blocked = detectBlock(html, status)

            StaticFetch(status, finalUrl, html, title, blocked)
        } catch (t: Throwable) {
            StaticFetch(0, url, "", "", null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Cloudflare interstitial, age gate or consent wall. */
    private fun detectBlock(html: String, status: Int): String? {
        val l = html.lowercase()
        return when {
            l.contains("just a moment") || l.contains("cf-challenge") ||
                l.contains("checking your browser") || l.contains("cf_chl_opt") ->
                "cloudflare challenge"
            status == 403 -> "HTTP 403"
            status == 429 -> "HTTP 429"
            l.contains("are you 18") || l.contains("age verification") ||
                l.contains("enter your age") || l.contains("over 18") ->
                "age wall"
            l.contains("accept cookies") && html.length < 4000 -> "consent wall"
            else -> null
        }
    }
}

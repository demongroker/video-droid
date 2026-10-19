package com.xerxes.videodroid

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the sniffer found on a page with no yt-dlp extractor. */
data class SniffedMedia(
    val url: String,
    val title: String,
    val referer: String,
    val userAgent: String,
)

/**
 * Last-resort fallback for sites yt-dlp has no extractor for.
 *
 * Loads the page in an off-screen WebView (desktop UA, JS on), watches the
 * network for media requests and also reads <video>/<source> and og:video out
 * of the DOM. Everything runs on the main looper because WebView requires it;
 * the caller blocks on a latch for at most [MAX_WAIT_MS].
 */
object PageSniffer {

    private const val MAX_WAIT_MS = 20_000L
    private const val POLL_MS = 400L

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Hosts that only ever serve ads, trackers or analytics. */
    private val JUNK_HOSTS = listOf(
        "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
        "adservice", "adsystem", "adnxs", "taboola", "outbrain", "popads",
        "exoclick", "juicyads", "trafficjunky", "histats", "scorecardresearch",
        "facebook.com/tr", "pixel", "analytics", "beacon",
    )

    private val MEDIA_EXT = Regex("""\.(mp4|m3u8|mpd|webm|m4v)(\?|$|&)""", RegexOption.IGNORE_CASE)

    fun sniff(context: Context, pageUrl: String): SniffedMedia? {
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<SniffedMedia>(1)
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            try {
                webView = buildWebView(context, pageUrl, latch, out)
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        try {
            latch.await(MAX_WAIT_MS + 3_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        Handler(Looper.getMainLooper()).post {
            runCatching {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
            }
        }
        return out[0]
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        context: Context,
        pageUrl: String,
        latch: CountDownLatch,
        out: Array<SniffedMedia?>,
    ): WebView {
        val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
        val done = AtomicBoolean(false)
        val title = arrayOf("")

        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val u = request?.url?.toString().orEmpty()
                if (u.isNotEmpty() && MEDIA_EXT.containsMatchIn(u)) seen.add(u)
                return null
            }
        }

        wv.loadUrl(pageUrl)

        val handler = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()

        fun finish(found: SniffedMedia?) {
            if (done.compareAndSet(false, true)) {
                out[0] = found
                latch.countDown()
            }
        }

        fun evaluate() {
            if (done.get()) return
            if (System.currentTimeMillis() - started > MAX_WAIT_MS) {
                finish(best(seen, pageUrl, title[0]))
                return
            }
            // Pull DOM sources too — many players only set src via JS.
            wv.evaluateJavascript(
                """(function(){
                    var out=[];
                    document.querySelectorAll('video,source').forEach(function(e){
                        if(e.src) out.push(e.src);
                        if(e.currentSrc) out.push(e.currentSrc);
                        var d=e.getAttribute&&e.getAttribute('data-src'); if(d) out.push(d);
                    });
                    var og=document.querySelector('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
                    if(og&&og.content) out.push(og.content);
                    var t=(document.title||'').trim();
                    return JSON.stringify({u:out,t:t});
                })()"""
            ) { raw ->
                try {
                    val json = org.json.JSONObject(
                        raw?.replace("\\\"", "\"")?.trim('"')?.replace("\\\\", "\\") ?: "{}",
                    )
                    val arr = json.optJSONArray("u")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val u = arr.optString(i, "")
                            if (u.isNotEmpty()) seen.add(u)
                        }
                    }
                    val t = json.optString("t", "")
                    if (t.isNotEmpty() && title[0].isEmpty()) title[0] = t
                } catch (_: Throwable) {
                    // The string escaping from evaluateJavascript is messy;
                    // network capture alone is usually enough.
                }
                handler.postDelayed({ evaluate() }, POLL_MS)
            }
        }

        // Try to start the first player so the media request actually fires.
        wv.postDelayed({
            wv.evaluateJavascript(
                """(function(){
                    var v=document.querySelector('video');
                    if(v){ try{ v.muted=true; v.play(); }catch(e){} }
                    var b=document.querySelector('.play,.player,.vjs-big-play-button,[class*=play]');
                    if(b){ try{ b.click(); }catch(e){} }
                })()""",
                null,
            )
            evaluate()
        }, 1_500)

        return wv
    }

    /** Prefer a streaming master playlist, then the biggest-looking mp4. */
    private fun best(seen: List<String>, pageUrl: String, title: String): SniffedMedia? {
        val clean = synchronized(seen) { seen.toList() }
            .filter { it.startsWith("http") }
            .filter { candidate -> JUNK_HOSTS.none { candidate.lowercase().contains(it) } }
            .distinct()
        if (clean.isEmpty()) return null

        fun rank(u: String): Int {
            val l = u.lowercase()
            return when {
                l.contains(".m3u8") -> 100
                l.contains(".mpd") -> 90
                l.contains("1080") -> 80
                l.contains("720") -> 70
                l.contains(".mp4") -> 60
                l.contains(".webm") -> 50
                else -> 10
            }
        }

        val url = clean.maxByOrNull { rank(it) } ?: return null
        val cookies = CookieManager.getInstance().getCookie(pageUrl).orEmpty()
        return SniffedMedia(url, title, pageUrl, DESKTOP_UA).also {
            // Cookie header is passed separately to yt-dlp by the caller.
            lastCookies = cookies
        }
    }

    @Volatile
    var lastCookies: String = ""
        private set
}

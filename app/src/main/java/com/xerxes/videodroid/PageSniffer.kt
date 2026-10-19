package com.xerxes.videodroid

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLDecoder
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

/** A sniff attempt: the winner (if any) plus everything it considered. */
data class SniffResult(
    val media: SniffedMedia?,
    val candidates: List<String>,
    val frames: List<String>,
)

/**
 * Last-resort fallback for sites yt-dlp has no extractor for.
 *
 * Loads the page in an off-screen WebView (desktop UA, JS on), then:
 *  - forces lazyloaded iframes to actually load (`data-litespeed-src`,
 *    `data-src`) — on WordPress/LiteSpeed sites the real player lives there
 *    with `src="about:blank"`;
 *  - watches the network for media requests, including from nested frames;
 *  - decodes `?q=<base64>` player params, which some players use to carry the
 *    whole `<video><source>` markup;
 *  - reads `<video>/<source>`, `data-*`, `og:video`, `twitter:player`, JSON-LD
 *    and `file:` / `sources:` blobs out of the DOM and inline scripts;
 *  - dismisses age gates / consent buttons and taps the player centre.
 *
 * Runs on the main looper (WebView requirement); the caller blocks on a latch.
 */
object PageSniffer {

    private const val MAX_WAIT_MS = 40_000L
    private const val POLL_MS = 500L

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val JUNK_HOSTS = listOf(
        "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
        "adservice", "adsystem", "adnxs", "taboola", "outbrain", "popads",
        "exoclick", "juicyads", "trafficjunky", "histats", "scorecardresearch",
        "pixel", "analytics", "beacon", "/tr?",
    )

    private val MEDIA_EXT = Regex("""\.(mp4|m3u8|mpd|webm|m4v)(\?|$|&)""", RegexOption.IGNORE_CASE)

    fun sniff(context: Context, pageUrl: String, onStatus: (String) -> Unit = {}): SniffResult {
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<SniffResult>(1)
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            try {
                webView = buildWebView(context, pageUrl, latch, out, onStatus)
            } catch (e: Throwable) {
                out[0] = SniffResult(null, listOf("webview error: ${e.message}"), emptyList())
                latch.countDown()
            }
        }

        try {
            latch.await(MAX_WAIT_MS + 5_000L, TimeUnit.MILLISECONDS)
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
        return out[0] ?: SniffResult(null, listOf("timed out with no candidates"), emptyList())
    }

    /** Players often hide the real file inside a base64 `?q=` parameter. */
    private fun expandEncoded(urls: List<String>, sink: MutableList<String>) {
        for (u in urls) {
            val q = Regex("""[?&]q=([^&]+)""").find(u)?.groupValues?.get(1) ?: continue
            try {
                val decodedParam = URLDecoder.decode(q, "UTF-8")
                val bytes = Base64.decode(decodedParam, Base64.DEFAULT)
                val text = String(bytes, Charsets.UTF_8)
                val inner = URLDecoder.decode(text, "UTF-8")
                Regex("""(?:src|file)\s*=\s*["']([^"']+)["']""")
                    .findAll(inner)
                    .forEach { sink.add(it.groupValues[1]) }
                Regex("""https?://[^"'\s<>]+""")
                    .findAll(inner)
                    .filter { MEDIA_EXT.containsMatchIn(it.value) }
                    .forEach { sink.add(it.value) }
            } catch (_: Throwable) {
                // not base64 / not our format
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        context: Context,
        pageUrl: String,
        latch: CountDownLatch,
        out: Array<SniffResult?>,
        onStatus: (String) -> Unit,
    ): WebView {
        val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
        val frames: MutableList<String> = Collections.synchronizedList(ArrayList())
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
            setSupportMultipleWindows(false)
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

        fun finish(result: SniffResult) {
            if (done.compareAndSet(false, true)) {
                out[0] = result
                latch.countDown()
            }
        }

        fun collect(): SniffResult {
            val snapshot = synchronized(seen) { seen.toList() }
            val expanded = ArrayList(snapshot)
            expandEncoded(snapshot, expanded)
            val frameList = synchronized(frames) { frames.toList() }
            val media = best(expanded, frameList.firstOrNull() ?: pageUrl, title[0])
            return SniffResult(media, expanded.distinct(), frameList)
        }

        fun poll() {
            if (done.get()) return
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > MAX_WAIT_MS) {
                finish(collect())
                return
            }
            onStatus("Searching page for video… (${elapsed / 1000}s)")

            wv.evaluateJavascript(
                """(function(){
                    var urls=[],frames=[];
                    // 1. Force lazyloaded iframes to actually load.
                    document.querySelectorAll('iframe').forEach(function(f){
                        var real=f.getAttribute('data-litespeed-src')||f.getAttribute('data-src')||
                                 f.getAttribute('data-video')||f.getAttribute('data-original');
                        if(real){ try{ if(f.src!==real) f.src=real; }catch(e){} frames.push(real); }
                        if(f.src && f.src.indexOf('about:')!==0) frames.push(f.src);
                    });
                    // 2. Age gate / consent buttons.
                    var labels=['18','enter','agree','accept','continue','consent','yes','ok'];
                    document.querySelectorAll('button,a,div[role=button]').forEach(function(b){
                        var t=(b.textContent||'').trim().toLowerCase();
                        if(t && t.length<24 && labels.some(function(l){return t.indexOf(l)>=0;})){
                            try{ b.click(); }catch(e){}
                        }
                    });
                    // 3. Media in the DOM.
                    document.querySelectorAll('video,source').forEach(function(e){
                        [e.src,e.currentSrc,e.getAttribute&&e.getAttribute('data-src'),
                         e.getAttribute&&e.getAttribute('data-video')].forEach(function(u){ if(u) urls.push(u); });
                    });
                    // 4. meta / JSON-LD.
                    document.querySelectorAll('meta').forEach(function(m){
                        var p=(m.getAttribute('property')||m.getAttribute('name')||'').toLowerCase();
                        if(p.indexOf('og:video')>=0||p.indexOf('twitter:player')>=0){ if(m.content) urls.push(m.content); }
                    });
                    document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){
                        var t=s.textContent||'';
                        var m=t.match(/"(?:contentUrl|embedUrl)"\s*:\s*"([^"]+)"/g)||[];
                        m.forEach(function(x){ var u=x.split('":"')[1]; if(u) urls.push(u.replace(/"$/,'')); });
                    });
                    // 5. Player setup blobs.
                    var html=document.documentElement.innerHTML;
                    (html.match(/(?:file|src|contentUrl)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|mpd)[^"']*)/gi)||[])
                        .forEach(function(x){ urls.push(x.replace(/^[^"']*["']/,'')); });
                    return JSON.stringify({u:urls,f:frames,t:(document.title||'').trim()});
                })()"""
            ) { raw ->
                try {
                    val json = org.json.JSONObject(unescapeJsString(raw))
                    json.optJSONArray("u")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val u = arr.optString(i, "")
                            if (u.isNotEmpty()) seen.add(u)
                        }
                    }
                    json.optJSONArray("f")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val u = arr.optString(i, "")
                            if (u.isNotEmpty()) frames.add(u)
                        }
                    }
                    val t = json.optString("t", "")
                    if (t.isNotEmpty() && title[0].isEmpty()) title[0] = t
                } catch (_: Throwable) {
                    // escaping from evaluateJavascript is messy; network capture stands alone
                }
                handler.postDelayed({ poll() }, POLL_MS)
            }
        }

        wv.postDelayed({
            wv.evaluateJavascript(
                """(function(){
                    var v=document.querySelector('video');
                    if(v){ try{ v.muted=true; v.play(); }catch(e){} }
                    document.querySelectorAll('.vjs-big-play-button,.play,[class*=play]').forEach(function(b){
                        try{ b.click(); }catch(e){}
                    });
                    window.scrollTo(0, document.body.scrollHeight/3);
                })()""",
                null,
            )
            poll()
        }, 1_500)

        return wv
    }

    private fun unescapeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return "{}"
        return raw.trim()
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    /** Prefer a streaming master playlist, then the biggest-looking file. */
    private fun best(seen: List<String>, referer: String, title: String): SniffedMedia? {
        val clean = seen
            .filter { it.startsWith("http") }
            .filter { c -> JUNK_HOSTS.none { c.lowercase().contains(it) } }
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
        return SniffedMedia(url, title, referer, DESKTOP_UA)
    }
}

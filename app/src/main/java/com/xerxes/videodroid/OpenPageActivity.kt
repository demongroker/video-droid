package com.xerxes.videodroid

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class OpenPageActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var sniffStatus: TextView
    private lateinit var sniffDownload: Button
    private lateinit var adblockToggle: Button
    private var sniffed: String? = null
    private var blobOnly = false
    private var pageHost: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_page)

        AdBlock.ensureLoaded(this)

        web = findViewById(R.id.pageWebView)
        sniffStatus = findViewById(R.id.sniffStatus)
        sniffDownload = findViewById(R.id.sniffDownload)
        adblockToggle = findViewById(R.id.adblockToggle)
        sniffDownload.setOnClickListener { downloadSniffed() }
        paintAdblock()
        adblockToggle.setOnClickListener {
            AdBlock.setEnabled(this, !AdBlock.isEnabled(this))
            paintAdblock()
        }

        val pageUrl = resolvePageUrl()
        if (pageUrl.isEmpty()) {
            sniffStatus.text = "No page URL."
            sniffDownload.isEnabled = false
            web.visibility = android.view.View.GONE
            return
        }
        pageHost = try {
            Uri.parse(pageUrl).host
        } catch (_: Exception) {
            null
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.addJavascriptInterface(SniffBridge(), "VideoDroidSniff")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                consider(request.url.toString(), request.requestHeaders?.get("Accept"))
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                consider(url, request.requestHeaders?.get("Accept"))
                if (AdBlock.isEnabled(this@OpenPageActivity) &&
                    AdBlock.shouldBlock(url, pageHost)
                ) {
                    return AdBlock.emptyOk()
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectSniffer()
                runOnUiThread { refreshStatus() }
            }
        }
        web.loadUrl(pageUrl)
    }

    private fun paintAdblock() {
        val on = AdBlock.isEnabled(this)
        adblockToggle.text = if (on) "Adblock On" else "Adblock Off"
    }

    private fun injectSniffer() {
        web.evaluateJavascript(SNIFF_JS, null)
    }

    private inner class SniffBridge {
        @JavascriptInterface
        fun found(url: String?) {
            if (url.isNullOrBlank()) return
            consider(url, null)
        }

        @JavascriptInterface
        fun blobHint() {
            runOnUiThread {
                if (sniffed == null) {
                    blobOnly = true
                    refreshStatus()
                }
            }
        }
    }

    private fun refreshStatus() {
        when {
            sniffed != null -> {
                sniffStatus.text = "Video found"
                sniffDownload.isEnabled = true
            }
            blobOnly -> {
                sniffStatus.text = "Player uses blob. Play the video."
                sniffDownload.isEnabled = false
            }
            else -> {
                sniffStatus.text = "Looking for video…"
                sniffDownload.isEnabled = false
            }
        }
    }

    private fun consider(url: String, accept: String?) {
        val u = url.trim()
        if (u.startsWith("blob:") || u.startsWith("data:")) {
            runOnUiThread {
                if (sniffed == null) {
                    blobOnly = true
                    refreshStatus()
                }
            }
            return
        }
        if (sniffed != null) return
        if (!looksLikeMedia(u, accept)) return
        sniffed = u
        blobOnly = false
        runOnUiThread { refreshStatus() }
    }

    private fun downloadSniffed() {
        val media = sniffed ?: return
        val result = Intent().putExtra(EXTRA_MEDIA, media)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        try {
            web.stopLoading()
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun resolvePageUrl(): String {
        val extra = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        if (extra.isNotEmpty()) return extra
        return loadJobUrl(this)
    }

    companion object {
        const val EXTRA_URL = "page_url"
        const val EXTRA_MEDIA = "media_url"
        private const val PREFS = "videodroid"
        private const val KEY_JOB_URL = "last_job_url"

        fun storeJobUrl(context: android.content.Context, url: String) {
            val u = url.trim()
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY_JOB_URL, u)
                .apply()
        }

        fun loadJobUrl(context: android.content.Context): String {
            return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_JOB_URL, "")
                .orEmpty()
                .trim()
        }

        private val SNIFF_JS = """
            (function(){
              if (window.__vdSniffInstalled) { try { window.__vdScan(); } catch(e){} return; }
              window.__vdSniffInstalled = true;
              function report(u){
                if (!u || typeof u !== 'string') return;
                if (u.indexOf('blob:')===0 || u.indexOf('data:')===0) {
                  try { VideoDroidSniff.blobHint(); } catch(e){}
                  return;
                }
                try { VideoDroidSniff.found(u); } catch(e){}
              }
              function scanVideos(){
                var vs = document.querySelectorAll('video');
                for (var i=0;i<vs.length;i++){
                  var v = vs[i];
                  report(v.currentSrc || v.src);
                  var srcs = v.querySelectorAll('source');
                  for (var j=0;j<srcs.length;j++) report(srcs[j].src);
                }
              }
              function scanPerf(){
                try {
                  var es = performance.getEntriesByType('resource');
                  for (var i=0;i<es.length;i++){
                    var n = es[i].name || '';
                    if (/m3u8|mp4|mpd|webm|\.ts(\?|$)/i.test(n)) report(n);
                  }
                } catch(e){}
              }
              window.__vdScan = function(){ scanVideos(); scanPerf(); };
              try {
                var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (desc && desc.set) {
                  Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                    configurable: true,
                    enumerable: desc.enumerable,
                    get: desc.get,
                    set: function(v){ report(v); return desc.set.call(this, v); }
                  });
                }
              } catch(e){}
              try {
                var so = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'srcObject');
                if (so && so.set) {
                  Object.defineProperty(HTMLMediaElement.prototype, 'srcObject', {
                    configurable: true,
                    enumerable: so.enumerable,
                    get: so.get,
                    set: function(v){
                      try { VideoDroidSniff.blobHint(); } catch(e){}
                      return so.set.call(this, v);
                    }
                  });
                }
              } catch(e){}
              try {
                var po = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m,u){
                  try { if (typeof u === 'string' && /m3u8|mp4|mpd|webm/i.test(u)) report(u); } catch(e){}
                  return po.apply(this, arguments);
                };
              } catch(e){}
              try {
                var ofetch = window.fetch;
                if (ofetch) {
                  window.fetch = function(input, init){
                    try {
                      var u = (typeof input === 'string') ? input : (input && input.url);
                      if (u && /m3u8|mp4|mpd|webm/i.test(u)) report(u);
                    } catch(e){}
                    return ofetch.apply(this, arguments);
                  };
                }
              } catch(e){}
              scanVideos();
              scanPerf();
              setInterval(window.__vdScan, 1500);
            })();
        """.trimIndent()

        fun looksLikeMedia(url: String, accept: String? = null): Boolean {
            val u = url.lowercase()
            if (u.startsWith("blob:") || u.startsWith("data:")) return false
            if (u.contains("video.twimg.com")) return true
            if (u.contains("googlevideo.com")) return true
            if (u.contains(".m3u8") || u.contains("application/vnd.apple.mpegurl")) return true
            if (u.contains(".mp4") || u.contains("video/mp4")) return true
            if (u.contains(".mpd") || u.contains(".webm") || u.contains(".ts")) return true
            val a = accept?.lowercase() ?: return false
            return a.contains("video/") || a.contains("mpegurl") || a.contains("application/dash+xml")
        }
    }
}

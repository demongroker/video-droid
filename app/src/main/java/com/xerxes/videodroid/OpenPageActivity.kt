package com.xerxes.videodroid

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
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
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val next = request.url
                if (!isHttpOrHttps(next)) return true
                consider(next.toString(), request.requestHeaders?.get("Accept"))
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (!isHttpOrHttps(uri)) return AdBlock.emptyOk()
                val url = uri.toString()
                consider(url, request.requestHeaders?.get("Accept"))
                if (AdBlock.isEnabled(this@OpenPageActivity) &&
                    AdBlock.shouldBlock(url, pageHost)
                ) {
                    return AdBlock.emptyOk()
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                runOnUiThread { refreshStatus() }
            }
        }
        if (!isHttpOrHttps(Uri.parse(pageUrl))) {
            sniffStatus.text = "No page URL."
            sniffDownload.isEnabled = false
            web.visibility = android.view.View.GONE
            return
        }
        web.loadUrl(pageUrl)
    }

    private fun paintAdblock() {
        val on = AdBlock.isEnabled(this)
        adblockToggle.text = if (on) "Adblock On" else "Adblock Off"
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
            if (u.isEmpty()) {
                clearJobUrl(context)
                return
            }
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY_JOB_URL, u)
                .apply()
        }

        fun clearJobUrl(context: android.content.Context) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .remove(KEY_JOB_URL)
                .apply()
        }

        fun loadJobUrl(context: android.content.Context): String {
            return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_JOB_URL, "")
                .orEmpty()
                .trim()
        }

        fun isHttpOrHttps(uri: Uri?): Boolean {
            if (uri == null) return false
            val scheme = uri.scheme?.lowercase() ?: return false
            return scheme == "https" || scheme == "http"
        }

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

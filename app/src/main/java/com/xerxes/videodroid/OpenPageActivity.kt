package com.xerxes.videodroid

import android.annotation.SuppressLint
import android.content.Intent
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
    private var sniffed: String? = null
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_page)

        val pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (pageUrl.isEmpty()) {
            finish()
            return
        }

        web = findViewById(R.id.pageWebView)
        sniffStatus = findViewById(R.id.sniffStatus)
        sniffDownload = findViewById(R.id.sniffDownload)
        sniffDownload.setOnClickListener { downloadSniffed() }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                consider(request.url.toString(), request.requestHeaders?.get("Accept"))
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                consider(request.url.toString(), request.requestHeaders?.get("Accept"))
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                finished = true
                if (sniffed == null) {
                    runOnUiThread {
                        if (sniffed == null) sniffStatus.text = "No video found on page"
                    }
                }
            }
        }
        web.loadUrl(pageUrl)
    }

    private fun consider(url: String, accept: String?) {
        if (sniffed != null) return
        if (!looksLikeMedia(url, accept)) return
        sniffed = url
        runOnUiThread {
            sniffStatus.text = "Video found"
            sniffDownload.isEnabled = true
        }
    }

    private fun downloadSniffed() {
        val media = sniffed ?: return
        val result = Intent().putExtra(EXTRA_MEDIA, media)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "page_url"
        const val EXTRA_MEDIA = "media_url"

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

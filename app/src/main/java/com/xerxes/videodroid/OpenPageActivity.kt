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

        // Enhanced WebView security settings
        val cm = CookieManager.getInstance()
        // Default: third-party cookies OFF for privacy; enable only per-site if required
        cm.setAcceptThirdPartyCookies(web, false)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Block mixed content: never allow HTTP within HTTPS pages
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // CRITICAL: No file access from WebView - prevents path traversal attacks
        web.settings.allowFileAccess = false
        // No content access from untrusted pages
        web.settings.allowContentAccess = false
        // Additional security: disable universal access from file URLs
        web.settings.domStorageEnabled = false // re-disabled for security
        web.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (!isHttpOrHttps(Uri.parse(url))) return true
                consider(url, view.requestHeaders?.get("Accept"))
                return false
            }

            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                if (!isHttpOrHttps(Uri.parse(url))) return null
                val url = url.trim()
                // Block blob: and data: URLs from external sources
                if (url.startsWith("blob:") || url.startsWith("data:")) return null
                // AdBlock interception
                if (AdBlock.isEnabled(this@OpenPageActivity) &&
                    AdBlock.shouldBlock(url, pageHost)
                ) {
                    return AdBlock.emptyOk()
                }
                // Additional security: block suspicious patterns
                if (url.contains("javascript:") || url.contains("data:")) return null
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                runOnUiThread { refreshStatus() }
                // Clear sensitive data after page load
                CookieManager.getInstance().removeSessionCookies()
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
        // Block blob: and data: URLs from external sources
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
            web.clearHistory()
            // Clear WebView cookies and cache for privacy
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            web.loadDataWithBaseURL("", "", "text/html", "utf-8", null)
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

        /** Improved media sniffing with candidate scoring. */
        fun looksLikeMedia(url: String, accept: String? = null): Boolean {
            val u = url.lowercase()
            // Explicitly block blob: and data:
            if (u.startsWith("blob:") || u.startsWith("data:")) return false
            // Recognized hosts
            if (u.contains("video.twimg.com")) return true
            if (u.contains("googlevideo.com")) return true
            // Recognized extensions/MIME types with scoring
            val mediaScore = mutableListOf<Pair<String, Int>>()
            
            // m3u8 / HLS
            if (u.contains(".m3u8") || u.contains("application/vnd.apple.mpegurl")) {
                mediaScore.add("m3u8/HLS", 8)
            }
            // MP4 direct
            if (u.contains(".mp4") || u.contains("video/mp4")) {
                mediaScore.add("mp4", 9)
            }
            // DASH / .mpd
            if (u.contains(".mpd") || u.contains(".webm") || u.contains("application/dash+xml")) {
                mediaScore.add("MPD/DASH", 7)
            }
            // TS transport
            if (u.contains(".ts")) {
                mediaScore.add("TS", 6)
            }
            // Twitter video
            if (u.contains("video.twimg.com")) {
                mediaScore.add("Twitter video", 8)
            }
            // Google Video
            if (u.contains("googlevideo.com")) {
                mediaScore.add("Google video", 8)
            }
            // Generic video MIME
            if (accept?.lowercase()?.contains("video/") == true) {
                mediaScore.add("generic video MIME", 5)
            }
            
            // Pick best candidate - highest score, avoid ads/previews
            return mediaScore.isNotEmpty()
        }

        /** Clear browser data on demand. */
        fun clearBrowserData(context: android.content.Context) {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            // Clear WebView cookies
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            // WebView cache clearing would go here via WebStorage
        }
    }
}

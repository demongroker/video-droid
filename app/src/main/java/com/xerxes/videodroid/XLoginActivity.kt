package com.xerxes.videodroid

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class XLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var hint: TextView
    private var exported = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_x_login)

        web = findViewById(R.id.xWebView)
        hint = findViewById(R.id.xLoginHint)
        findViewById<Button>(R.id.xDone).setOnClickListener { finish() }
        findViewById<Button>(R.id.xClear).setOnClickListener {
            XCookies.clear(this)
            exported = false
            hint.text = "Logged out. Sign in again if needed."
            web.loadUrl("https://x.com/login")
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                maybeExport(url)
            }
        }
        web.loadUrl("https://x.com/login")
    }

    private fun maybeExport(url: String) {
        val lower = url.lowercase()
        val onLogin = lower.contains("/login") || lower.contains("/i/flow/login") ||
            lower.contains("/i/flow/single_sign_on")
        if (onLogin) return
        val hostOk = lower.contains("://x.com") || lower.contains("://www.x.com") ||
            lower.contains("://twitter.com") || lower.contains("://www.twitter.com")
        if (!hostOk) return
        if (!XCookies.looksLoggedIn()) return
        if (XCookies.exportFromWebView(this)) {
            if (!exported) {
                exported = true
                hint.text = "Logged in. Cookies saved on this phone for downloads."
                Toast.makeText(this, "X login saved on device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}

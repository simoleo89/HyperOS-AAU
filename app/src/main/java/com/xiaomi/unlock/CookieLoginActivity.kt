package com.xiaomi.unlock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity

class CookieLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_COOKIE = "extracted_cookie"

        private val XIAOMI_DOMAINS = listOf(
            "https://account.xiaomi.com",
            "https://global.account.xiaomi.com",
            "https://c.mi.com",
            "https://sgp-api.buy.mi.com"
        )

        private const val START_URL =
            "https://c.mi.com/global/mio/index?page=unlock"
    }

    private lateinit var webView: WebView
    private var cookieAlreadyReturned = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val titleText = TextView(this).apply {
            text = "🔑  Login Xiaomi — Cookie automatico"
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(20, 8, 20, 8)
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        topBar.addView(titleText)
        topBar.addView(btnClose)

        val hintText = TextView(this).apply {
            text = "Effettua il login con il tuo account Xiaomi. Il cookie verrà estratto in automatico al termine."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        val statusText = TextView(this).apply {
            text = "Caricamento..."
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 11f
            setPadding(24, 8, 24, 8)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; 2210132C) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MiuiApp/5.4.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            removeAllCookies(null)
            flush()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                statusText.text = "🌐 $url"
                tryExtractCookie()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                tryExtractCookie()
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    statusText.text = "⏳ Caricamento $newProgress%..."
                }
            }
        }

        root.addView(topBar)
        root.addView(hintText)
        root.addView(statusText)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl(START_URL)
    }

    private fun tryExtractCookie() {
        if (cookieAlreadyReturned) return
        val cookieMgr = CookieManager.getInstance()

        for (domain in XIAOMI_DOMAINS) {
            val raw = cookieMgr.getCookie(domain) ?: continue
            if (raw.contains("serviceToken") && raw.contains("userId")) {
                cookieAlreadyReturned = true
                val result = Intent().apply { putExtra(EXTRA_COOKIE, raw) }
                setResult(Activity.RESULT_OK, result)
                finish()
                return
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else {
            setResult(Activity.RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
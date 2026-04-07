package com.xiaomi.unlock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            "https://sgp-api.buy.mi.com",
            "https://i.mi.com",
            "https://mi.com"
        )

        private const val START_URL =
            "https://account.xiaomi.com/pass/serviceLogin?_locale=en_US&bizDeviceType=0"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private var cookieAlreadyReturned = false
    private var currentUrl = ""

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!cookieAlreadyReturned) {
                tryExtractCookie()
                handler.postDelayed(this, 500)
            }
        }
    }

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

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val titleText = TextView(this).apply {
            text = "🔑  Xiaomi Login — Auto Cookie"
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(20, 8, 20, 8)
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }
        topBar.addView(titleText)
        topBar.addView(btnClose)

        val hintText = TextView(this).apply {
            text = "Log in with your Xiaomi account. Cookie will be extracted automatically."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(24, 10, 24, 10)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        statusText = TextView(this).apply {
            text = "Loading..."
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 11f
            setPadding(24, 6, 24, 6)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }

        // Debug area — mostra i cookie trovati in tempo reale
        debugText = TextView(this).apply {
            text = "Waiting for cookies..."
            setTextColor(Color.parseColor("#666666"))
            textSize = 9f
            setPadding(24, 4, 24, 4)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }

        // Pulsante manuale: se il polling non funziona, l'utente clicca e usa tutto ciò che c'è
        val btnManual = Button(this).apply {
            text = "📋  Use Current Cookie"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF6900"))
            setPadding(24, 16, 24, 16)
            setOnClickListener { forceExtractAnyCookie() }
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
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
                currentUrl = url
                statusText.text = "🌐 $url"
                CookieManager.getInstance().flush()
                tryExtractCookie()
                updateDebug()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    statusText.text = "⏳ Loading $newProgress%..."
                } else {
                    CookieManager.getInstance().flush()
                    tryExtractCookie()
                    updateDebug()
                }
            }
        }

        root.addView(topBar)
        root.addView(hintText)
        root.addView(statusText)
        root.addView(debugText)
        root.addView(btnManual)
        root.addView(webView)
        setContentView(root)

        handler.postDelayed(pollRunnable, 2000)
        webView.loadUrl(START_URL)
    }

    // Show all cookies found across all domains in the debug area
    private fun updateDebug() {
        val sb = StringBuilder()
        for (domain in XIAOMI_DOMAINS) {
            val c = CookieManager.getInstance().getCookie(domain)
            if (!c.isNullOrBlank()) {
                sb.append("[$domain]\n")
                // Show only cookie names, not values (too long)
                c.split(";").forEach { pair ->
                    sb.append("  ${pair.trim().substringBefore("=").trim()}\n")
                }
            }
        }
        debugText.text = if (sb.isEmpty()) "No cookies found yet..." else sb.toString()
    }

    // Automatic extraction — requires serviceToken + userId
    private fun tryExtractCookie() {
        if (cookieAlreadyReturned) return
        val cookieMgr = CookieManager.getInstance()

        for (domain in XIAOMI_DOMAINS) {
            val raw = cookieMgr.getCookie(domain) ?: continue
            if (raw.contains("serviceToken") && raw.contains("userId")) {
                returnCookie(raw)
                return
            }
        }
    }

    // Manual extraction — uses whatever cookies are available across all domains
    private fun forceExtractAnyCookie() {
        if (cookieAlreadyReturned) return
        val cookieMgr = CookieManager.getInstance()

        // Try with serviceToken first
        for (domain in XIAOMI_DOMAINS) {
            val raw = cookieMgr.getCookie(domain) ?: continue
            if (raw.contains("serviceToken")) {
                returnCookie(raw)
                return
            }
        }

        // Fallback: merge all cookies from all domains
        val merged = XIAOMI_DOMAINS
            .mapNotNull { cookieMgr.getCookie(it) }
            .filter { it.isNotBlank() }
            .joinToString("; ")

        if (merged.isNotBlank()) {
            returnCookie(merged)
        } else {
            statusText.text = "❌ No cookies found. Please log in first."
        }
    }

    private fun returnCookie(cookie: String) {
        cookieAlreadyReturned = true
        handler.removeCallbacks(pollRunnable)
        statusText.text = "✅ Cookie extracted!"
        setResult(Activity.RESULT_OK, Intent().apply { putExtra(EXTRA_COOKIE, cookie) })
        handler.postDelayed({ finish() }, 800)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else { setResult(Activity.RESULT_CANCELED); super.onBackPressed() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        webView.destroy()
        super.onDestroy()
    }
}

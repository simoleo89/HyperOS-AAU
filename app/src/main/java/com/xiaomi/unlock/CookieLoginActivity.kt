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
            "https://sgp-api.buy.mi.com"
        )

        // Pagina login standard Xiaomi — nessun sid specifico
        private const val START_URL =
            "https://account.xiaomi.com/pass/serviceLogin?_locale=en_US&bizDeviceType=0"

        // URL che indicano che siamo FUORI dalla pagina di login
        private val LOGIN_DOMAINS = listOf(
            "account.xiaomi.com",
            "global.account.xiaomi.com"
        )
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private var cookieAlreadyReturned = false

    // Polling periodico del cookie ogni 500ms dopo ogni pagina caricata
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
            text = "Effettua il login con il tuo account Xiaomi. Il cookie verrà estratto automaticamente."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        statusText = TextView(this).apply {
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
                // Forza flush dei cookie e prova estrazione
                CookieManager.getInstance().flush()
                tryExtractCookie()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    statusText.text = "⏳ Caricamento $newProgress%..."
                } else {
                    CookieManager.getInstance().flush()
                    tryExtractCookie()
                }
            }
        }

        root.addView(topBar)
        root.addView(hintText)
        root.addView(statusText)
        root.addView(webView)
        setContentView(root)

        // Avvia polling ogni 500ms
        handler.postDelayed(pollRunnable, 2000)

        webView.loadUrl(START_URL)
    }

    private fun tryExtractCookie() {
        if (cookieAlreadyReturned) return
        val cookieMgr = CookieManager.getInstance()

        for (domain in XIAOMI_DOMAINS) {
            val raw = cookieMgr.getCookie(domain) ?: continue
            // Cerca serviceToken — indica sessione autenticata valida
            if (raw.contains("serviceToken") && raw.contains("userId")) {
                cookieAlreadyReturned = true
                handler.removeCallbacks(pollRunnable)
                statusText.text = "✅ Cookie estratto!"

                val result = Intent().apply { putExtra(EXTRA_COOKIE, raw) }
                setResult(Activity.RESULT_OK, result)

                // Breve delay per mostrare il messaggio prima di chiudere
                handler.postDelayed({ finish() }, 800)
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
        handler.removeCallbacks(pollRunnable)
        webView.destroy()
        super.onDestroy()
    }
}

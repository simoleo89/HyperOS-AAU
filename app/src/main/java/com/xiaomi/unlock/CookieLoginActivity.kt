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

        // Step 1: login page
        private const val LOGIN_URL =
            "https://account.xiaomi.com/pass/serviceLogin?_locale=en_US&bizDeviceType=0"

        // Step 2: after login, go directly to unlock page to generate new_bbs_serviceToken
        private const val UNLOCK_URL =
            "https://c.mi.com/global/mio/index?page=unlock"

        private val ALL_DOMAINS = listOf(
            "https://account.xiaomi.com",
            "https://global.account.xiaomi.com",
            "https://c.mi.com",
            "https://sgp-api.buy.mi.com",
            "https://i.mi.com",
            "https://mi.com"
        )
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private lateinit var btnGoUnlock: Button
    private var cookieAlreadyReturned = false
    private var loginDone = false

    private val handler = Handler(Looper.getMainLooper())

    // Poll every 500ms
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!cookieAlreadyReturned) {
                tryExtract()
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
        val title = TextView(this).apply {
            text = "🔑  Xiaomi Login"
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 15f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(20, 8, 20, 8)
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }
        topBar.addView(title)
        topBar.addView(btnClose)

        statusText = TextView(this).apply {
            text = "Step 1: Log in with your Xiaomi account"
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(24, 10, 24, 10)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }

        // Button shown after login to navigate to unlock page
        btnGoUnlock = Button(this).apply {
            text = "Step 2 →  Go to Unlock Page"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(24, 14, 24, 14)
            textSize = 13f
            isEnabled = false
            setOnClickListener {
                statusText.text = "Step 2: Loading unlock page..."
                webView.loadUrl(UNLOCK_URL)
            }
        }

        debugText = TextView(this).apply {
            text = "Waiting for cookies..."
            setTextColor(Color.parseColor("#555555"))
            textSize = 9f
            setPadding(24, 4, 24, 4)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
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
            // Use Xiaomi Community user-agent to get proper cookies
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; 23116PN5BC) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36 " +
                "MiuiBrowser/18.4.50402 swan-mibrowser"
        }

        // Accept all cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            removeAllCookies(null)
            flush()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                statusText.text = url.take(60)
                CookieManager.getInstance().flush()

                // Detect login completion by URL change away from account.xiaomi.com
                val isLoginPage = url.contains("account.xiaomi.com") ||
                                  url.contains("serviceLogin") ||
                                  url.contains("oauth")

                if (!isLoginPage && !loginDone) {
                    loginDone = true
                    statusText.text = "✅ Login detected! Tap Step 2 to get cookie."
                    btnGoUnlock.isEnabled = true
                    btnGoUnlock.setBackgroundColor(Color.parseColor("#FF6900"))
                }

                // If we're on the unlock page, try extract immediately
                if (url.contains("c.mi.com") || url.contains("sgp-api")) {
                    statusText.text = "🔍 Extracting cookie..."
                    handler.postDelayed({ tryExtract() }, 1000)
                }

                updateDebug()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) {
                    CookieManager.getInstance().flush()
                    tryExtract()
                    updateDebug()
                }
            }
        }

        root.addView(topBar)
        root.addView(statusText)
        root.addView(btnGoUnlock)
        root.addView(debugText)
        root.addView(webView)
        setContentView(root)

        // Start polling
        handler.postDelayed(pollRunnable, 3000)

        webView.loadUrl(LOGIN_URL)
    }

    private fun updateDebug() {
        val sb = StringBuilder()
        for (domain in ALL_DOMAINS) {
            val c = CookieManager.getInstance().getCookie(domain) ?: continue
            if (c.isNotBlank()) {
                // Show cookie names only
                val names = c.split(";").map { it.trim().substringBefore("=").trim() }
                sb.append("[${domain.substringAfter("https://")}]: ${names.joinToString(", ")}\n")
            }
        }
        handler.post {
            debugText.text = if (sb.isEmpty()) "No cookies yet..." else sb.toString()
        }
    }

    private fun tryExtract() {
        if (cookieAlreadyReturned) return
        val mgr = CookieManager.getInstance()

        for (domain in ALL_DOMAINS) {
            val raw = mgr.getCookie(domain) ?: continue
            if (raw.contains("new_bbs_serviceToken") ||
                (raw.contains("serviceToken") && raw.contains("userId"))) {
                returnCookie(raw)
                return
            }
        }
    }

    private fun returnCookie(cookie: String) {
        cookieAlreadyReturned = true
        handler.removeCallbacks(pollRunnable)
        statusText.text = "✅ Cookie extracted!"
        statusText.setTextColor(Color.parseColor("#67C23A"))
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
    }

    private fun detectAndLogXiaomiPackage(): String? {
        // Use getLaunchIntentForPackage — works on HyperOS where getPackageInfo may fail
        for (pkg in XIAOMI_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                log("Xiaomi Community found: $pkg")
                return pkg
            }
        }
        log("⚠️ Xiaomi Community not found in known packages.")
        log("Scanning installed apps...")
        val installed = packageManager.getInstalledPackages(0)
        for (info in installed) {
            val name = info.packageName.lowercase()
            if (name.contains("xiaomi") || name.contains("mi.global") ||
                name.contains(".bbs") || name.contains("miui")) {
                log("  ${info.packageName}")
            }
        }
        return null
    }

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            log("Requesting VPN permission...")
            vpnLauncher.launch(intent)
        } else {
            log("VPN already authorized.")
            startInterceptor()
        }
    }

    private fun startInterceptor() {
        statusText.text = "🔍 Intercepting... Open Xiaomi Community now"
        btnStart.isEnabled = false
        btnOpenXiaomi.isEnabled = true
        log("VPN interceptor started. Waiting for traffic...")
        startService(Intent(this, CookieInterceptVpnService::class.java).apply {
            action = CookieInterceptVpnService.ACTION_START
        })
    }

    private fun openXiaomiCommunity() {
        val pkg = detectAndLogXiaomiPackage()
        if (pkg != null) {
            try {
                startActivity(packageManager.getLaunchIntentForPackage(pkg))
                log("Opened $pkg. Go to ME → Unlock bootloader → Apply.")
            } catch (e: Exception) {
                log("Error opening $pkg: ${e.message}")
            }
        } else {
            log("❌ Open Xiaomi Community manually.")
        }
    }

    private fun returnCookie(cookie: String) {
        stopVpnService()
        setResult(Activity.RESULT_OK, Intent().apply { putExtra(EXTRA_COOKIE, cookie) })
        finish()
    }

    private fun stopVpnService() {
        startService(Intent(this, CookieInterceptVpnService::class.java).apply {
            action = CookieInterceptVpnService.ACTION_STOP
        })
    }

    private fun stopAndExit(canceled: Boolean) {
        stopVpnService()
        if (canceled) setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun log(msg: String) {
        handler.post { logText.append("\n> $msg") }
    }

    override fun onDestroy() {
        try { unregisterReceiver(cookieReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}

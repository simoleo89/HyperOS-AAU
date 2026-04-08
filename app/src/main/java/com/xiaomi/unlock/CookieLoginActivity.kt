package com.xiaomi.unlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class CookieLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_COOKIE = "extracted_cookie"

        // All known Xiaomi Community package names
        private val XIAOMI_PACKAGES = listOf(
            "com.mi.global.bbs",          // Global
            "com.xiaomi.miui.overseas",   // Alternative global
            "com.mi.miui.overseas",       // Another variant
            "com.mi.global.community",    // Community variant
            "com.xiaomi.bbs",             // China
            "com.mi.bbs"                  // China alt
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnOpenXiaomi: Button
    private var cookieFound = false

    private val cookieReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CookieInterceptVpnService.ACTION_LOG) {
                    log(intent.getStringExtra(CookieInterceptVpnService.EXTRA_LOG) ?: "")
                    return
                }
                if (intent.action == CookieInterceptVpnService.ACTION_COOKIE_FOUND) {
                val cookie = intent.getStringExtra(CookieInterceptVpnService.EXTRA_COOKIE) ?: return
                if (!cookieFound) {
                    cookieFound = true
                    log("✅ Cookie captured!")
                    statusText.text = "✅ Cookie captured!"
                    statusText.setTextColor(Color.parseColor("#67C23A"))
                    handler.postDelayed({ returnCookie(cookie) }, 1000)
                }
            }
        }
    }

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            log("VPN permission granted.")
            startInterceptor()
        } else {
            log("❌ VPN permission denied.")
            statusText.text = "VPN permission required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(CookieInterceptVpnService.ACTION_COOKIE_FOUND).also {
            it.addAction(CookieInterceptVpnService.ACTION_LOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cookieReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cookieReceiver, filter)
        }

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
            text = "🔑  Auto Cookie Interceptor"
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
            setOnClickListener { stopAndExit(canceled = true) }
        }
        topBar.addView(titleText)
        topBar.addView(btnClose)

        val hint = TextView(this).apply {
            text = "Steps:\n" +
                   "1. Tap START\n" +
                   "2. Allow VPN when asked\n" +
                   "3. Tap 'Open Xiaomi Community'\n" +
                   "4. Go to ME → Unlock bootloader\n" +
                   "5. Tap 'Apply for unlocking'\n" +
                   "6. Cookie captured automatically ✅"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        statusText = TextView(this).apply {
            text = "Ready — tap START"
            setTextColor(Color.parseColor("#FF6900"))
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(24, 14, 24, 14)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 180
            )
            setBackgroundColor(Color.BLACK)
        }
        logText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 10f
            setPadding(16, 8, 16, 8)
        }
        scroll.addView(logText)

        btnStart = Button(this).apply {
            text = "▶  START — Intercept Cookie"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF6900"))
            setPadding(24, 20, 24, 20)
            textSize = 15f
            setOnClickListener { requestVpnAndStart() }
        }

        btnOpenXiaomi = Button(this).apply {
            text = "📱  Open Xiaomi Community"
            setTextColor(Color.parseColor("#FF6900"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 16, 24, 16)
            textSize = 13f
            isEnabled = false
            setOnClickListener { openXiaomiCommunity() }
        }

        root.addView(topBar)
        root.addView(hint)
        root.addView(statusText)
        root.addView(scroll)
        root.addView(btnStart)
        root.addView(btnOpenXiaomi)
        setContentView(root)

        // Auto-detect installed package and show it in log
        handler.postDelayed({ detectXiaomiPackage() }, 500)
    }

    private fun detectXiaomiPackage(): String? {
        // Try direct launch intent first (more reliable on HyperOS)
        for (pkg in XIAOMI_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                log("Xiaomi Community found: $pkg")
                return pkg
            }
        }
        for (pkg in XIAOMI_PACKAGES) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                log("Xiaomi Community found: $pkg")
                return pkg
            } catch (_: Exception) {}
        }
        // Fallback: scan all installed apps for "xiaomi" or "mi.global" or "bbs"
        val installed = packageManager.getInstalledPackages(0)
        for (info in installed) {
            val name = info.packageName.lowercase()
            if ((name.contains("xiaomi") || name.contains("mi.global") || name.contains(".bbs"))
                && name.contains("community", ignoreCase = true).not()) {
                if (name.contains("bbs") || name.contains("community") || name.contains("global.bbs")) {
                    log("Found possible Xiaomi app: ${info.packageName}")
                    return info.packageName
                }
            }
        }
        log("⚠️ Could not auto-detect Xiaomi Community package.")
        log("Listing ALL installed packages:")
        for (info in installed) {
            log("  ${info.packageName}")
        }
        log("--- Xiaomi-related only ---")
        log("Listing all Xiaomi-related apps:")
        for (info in installed) {
            if (info.packageName.contains("xiaomi", ignoreCase = true) ||
                info.packageName.contains("miui", ignoreCase = true) ||
                info.packageName.contains("mi.global", ignoreCase = true)) {
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
        val pkg = detectXiaomiPackage()
        if (pkg != null) {
            try {
                startActivity(packageManager.getLaunchIntentForPackage(pkg))
                log("Opened $pkg. Go to ME → Unlock bootloader → Apply.")
            } catch (e: Exception) {
                log("Error opening $pkg: ${e.message}")
            }
        } else {
            log("❌ Xiaomi Community not found. Open it manually.")
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

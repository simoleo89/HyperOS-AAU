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

        private val XIAOMI_PACKAGES = listOf(
            "com.mi.global.bbs",
            "com.xiaomi.miui.overseas",
            "com.mi.miui.overseas",
            "com.mi.global.community",
            "com.xiaomi.bbs",
            "com.mi.bbs"
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
            when (intent.action) {
                CookieInterceptVpnService.ACTION_LOG -> {
                    log(intent.getStringExtra(CookieInterceptVpnService.EXTRA_LOG) ?: "")
                }
                CookieInterceptVpnService.ACTION_COOKIE_FOUND -> {
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

        val filter = IntentFilter().apply {
            addAction(CookieInterceptVpnService.ACTION_COOKIE_FOUND)
            addAction(CookieInterceptVpnService.ACTION_LOG)
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

        handler.postDelayed({ detectAndLogXiaomiPackage() }, 500)
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

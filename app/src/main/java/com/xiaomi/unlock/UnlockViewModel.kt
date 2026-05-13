package com.xiaomi.unlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ntp.NTPUDPClient
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class UnlockViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("unlock_prefs", Context.MODE_PRIVATE)

    var cookie by mutableStateOf(prefs.getString(KEY_COOKIE, "") ?: "")
    var isRunning by mutableStateOf(false)
    var isTestingCookie by mutableStateOf(false)
    var caffeineMode by mutableStateOf(prefs.getBoolean(KEY_CAFFEINE, false))
    var maxTriggers by mutableStateOf(prefs.getString(KEY_MAX_TRIGGERS, "4") ?: "4")

    var latencyMs by mutableStateOf<Long?>(null)
    var ntpOffsetMs by mutableStateOf<Long?>(null)
    var countdownText by mutableStateOf("Ready")

    val logs = mutableStateListOf<String>()
    val waves = mutableStateListOf<WaveStatus>()

    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val userAgent = "okhttp/4.12.0"
    private val unlockUrl = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
    private val unlockHost = "sgp-api.buy.mi.com"
    private val ntpServer = "pool.ntp.org"

    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    companion object {
        private const val CHANNEL_ID = "unlock_result_channel"
        private const val NOTIFICATION_ID = 1001
        private const val KEY_COOKIE = "saved_cookie"
        private const val KEY_CAFFEINE = "caffeine_mode"
        private const val KEY_MAX_TRIGGERS = "max_triggers"
        private const val BRACKET_HALF_MS = 60L
    }

    init {
        createNotificationChannel()
    }

    fun setCookie(value: String) {
        cookie = value
        prefs.edit().putString(KEY_COOKIE, value).apply()
    }

    fun setCaffeineMode(enabled: Boolean) {
        caffeineMode = enabled
        prefs.edit().putBoolean(KEY_CAFFEINE, enabled).apply()
    }

    fun setMaxTriggers(value: String) {
        maxTriggers = value
        prefs.edit().putString(KEY_MAX_TRIGGERS, value).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctx = getApplication<Application>()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Unlock Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for bootloader unlock attempt results"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun showSuccessNotification(message: String) {
        val ctx = getApplication<Application>()
        try {
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ Unlock Approved!")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            log("[Notify] Permission denied — could not show notification")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val ctx = getApplication<Application>()
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HyperOSAAU::UnlockWakeLock"
            ).apply { acquire(4 * 60 * 60 * 1000L) }
            log("[WakeLock] Acquired — CPU will stay active")
        } catch (e: Exception) {
            log("[WakeLock] Error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) { it.release(); log("[WakeLock] Released") } }
            wakeLock = null
        } catch (e: Exception) {
            log("[WakeLock] Release error: ${e.message}")
        }
    }

    private fun log(message: String) {
        viewModelScope.launch(Dispatchers.Main) { logs.add(message) }
    }

    fun startProcess() {
        if (cookie.isBlank()) {
            log("[!] Cookie cannot be empty.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isRunning = true
                isTestingCookie = true
            }
            log("=".repeat(40))
            log("Starting Xiaomi BL Unlock Automator (Pro Mode)...")

            // 1. Test cookie before doing anything that costs resources.
            log("[Test] Verifying cookie...")
            val isValid = testCookie()
            withContext(Dispatchers.Main) { isTestingCookie = false }

            if (!isValid) {
                log("[!] Cookie rejected (need login). It may have expired. Please paste a new one.")
                withContext(Dispatchers.Main) { isRunning = false }
                return@launch
            }
            log("[✓] Cookie is valid! Setting up...")

            acquireWakeLock()

            // 2. NTP sync
            log("[NTP] Syncing clock initially...")
            val offset = getNtpOffset()
            withContext(Dispatchers.Main) { ntpOffsetMs = offset }
            log("[NTP] Clock offset: ${offset}ms")

            // 3. Compute target = next Beijing midnight
            val targetUtcMs = getNextBeijingMidnightMs()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'CST'", Locale.US)
                .apply { timeZone = beijingTz }
            log("[Target] ${sdf.format(Date(targetUtcMs))} (Beijing Midnight)")

            // 4. Wait until 10s before target so we can measure latency on a warm path.
            val pingTimeUtcMs = targetUtcMs - 10_000L
            waitUntil(pingTimeUtcMs, "Ping")
            if (!isRunning) { releaseWakeLock(); return@launch }

            // 5. Measure JIT latency
            log("[Latency] 23:59:50 reached! Measuring final latency...")
            withContext(Dispatchers.Main) { countdownText = "Pinging..." }
            val lat = measureLatency()
            withContext(Dispatchers.Main) { latencyMs = lat }
            log("[Latency] Final measured latency: ${lat}ms")

            // 6. Build wave bracket
            val triggerCount = (maxTriggers.toIntOrNull() ?: 4).coerceAtLeast(1)
            log("[Config] Firing $triggerCount trigger(s)")

            val baseSendTimeUtcMs = targetUtcMs - lat
            val offsets: List<Long> = if (triggerCount == 1) {
                listOf(0L)
            } else {
                (0 until triggerCount).map { i ->
                    -BRACKET_HALF_MS + (2 * BRACKET_HALF_MS * i) / (triggerCount - 1)
                }
            }

            withContext(Dispatchers.Main) {
                waves.clear()
                offsets.forEachIndexed { idx, offsetMs ->
                    val label = if (offsetMs >= 0) "+${offsetMs}ms" else "${offsetMs}ms"
                    waves.add(WaveStatus(idx + 1, label))
                }
            }

            // 7. Wait until the very first wave fires
            val wave1SendTimeUtcMs = baseSendTimeUtcMs + offsets.first()
            waitUntil(wave1SendTimeUtcMs, "Fire")
            if (!isRunning) { releaseWakeLock(); return@launch }

            withContext(Dispatchers.Main) { countdownText = "FIRING" }
            log("===")

            // 8. Schedule each wave by absolute target so they fire independently of
            //    the cumulative latency of preceding launches.
            val waveJobs = offsets.mapIndexed { idx, offsetMs ->
                viewModelScope.launch(Dispatchers.IO) {
                    val absoluteFire = baseSendTimeUtcMs + offsetMs
                    val delayMs = absoluteFire -
                        (System.currentTimeMillis() + (ntpOffsetMs ?: 0L))
                    if (delayMs > 0) delay(delayMs)

                    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                        .apply { timeZone = beijingTz }
                        .format(Date(System.currentTimeMillis() + (ntpOffsetMs ?: 0L)))
                    val label = if (offsetMs >= 0) "+${offsetMs}ms" else "${offsetMs}ms"
                    log("[Spam ${idx + 1}] Launched at $ts CST ($label bracket)")
                    withContext(Dispatchers.Main) {
                        if (idx in waves.indices) waves[idx].state = WaveState.SENDING
                    }
                    sendWave(idx + 1)
                }
            }

            // 9. Wait for the actual responses instead of a fixed 3s timer.
            waveJobs.forEach { it.join() }

            log("[Done] Process Complete.")
            releaseWakeLock()
            withContext(Dispatchers.Main) {
                isRunning = false
                countdownText = "Done"
            }
        }
    }

    fun stopProcess() {
        isRunning = false
        releaseWakeLock()
        log("[!] User aborted.")
        viewModelScope.launch(Dispatchers.Main) { countdownText = "Aborted" }
    }

    override fun onCleared() {
        super.onCleared()
        releaseWakeLock()
    }

    private suspend fun waitUntil(targetUtcMs: Long, label: String) {
        while (isRunning) {
            val nowAccurate = System.currentTimeMillis() + (ntpOffsetMs ?: 0L)
            val remaining = targetUtcMs - nowAccurate
            if (remaining <= 0) break

            val text = when {
                remaining > 60_000 -> {
                    val h = remaining / 3_600_000
                    val m = (remaining % 3_600_000) / 60_000
                    val s = (remaining % 60_000) / 1_000
                    String.format("$label in %02dh %02dm %02ds", h, m, s)
                }
                remaining > 3_000 -> String.format("$label in %.2fs", remaining / 1000.0)
                else -> String.format("$label in %.3fs", remaining / 1000.0)
            }
            withContext(Dispatchers.Main) { countdownText = text }

            val sleep = when {
                remaining > 60_000 -> 1000L
                remaining > 3_000 -> 50L
                else -> minOf(remaining, 10L)
            }
            delay(sleep)
        }
    }

    private fun buildHeaders(reqBuilder: Request.Builder): Request.Builder = reqBuilder
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("Connection", "Keep-Alive")
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Cookie", cookie)
        .header("Host", unlockHost)
        .header("User-Agent", userAgent)

    private fun testCookie(): Boolean {
        return try {
            val reqBody = "{\"is_retry\":false}"
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = buildHeaders(Request.Builder().url(unlockUrl).post(reqBody)).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val msg = json.optString("msg", "")
                val data = json.optJSONObject("data")
                val result = data?.optInt("apply_result", -1) ?: -1
                log("[Test] HTTP ${resp.code} | msg=$msg | result=$result ${getResultMeaning(result)}")
                msg != "need login"
            }
        } catch (e: Exception) {
            log("[Test] Error: ${e.message}")
            false
        }
    }

    private fun getNtpOffset(): Long {
        return try {
            val ntpClient = NTPUDPClient()
            ntpClient.setDefaultTimeout(Duration.ofMillis(5000))
            ntpClient.open()
            val info = ntpClient.getTime(InetAddress.getByName(ntpServer))
            info.computeDetails()
            ntpClient.close()
            info.offset ?: 0L
        } catch (e: Exception) {
            log("[NTP Error] ${e.message} - Using 0 offset")
            0L
        }
    }

    private fun measureLatency(): Long {
        val times = mutableListOf<Long>()
        val pingClient = client.newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        repeat(5) {
            try {
                val t0 = System.currentTimeMillis()
                val req = Request.Builder().url("https://$unlockHost").head().build()
                pingClient.newCall(req).execute().close()
                times.add(System.currentTimeMillis() - t0)
            } catch (_: Exception) { /* ignore single-sample failures */ }
        }
        if (times.isEmpty()) {
            log("[Latency] Could not measure — defaulting to 300ms")
            return 300L
        }
        // Drop the worst sample to reduce spike sensitivity.
        val filtered = if (times.size >= 3) times.sorted().dropLast(1) else times
        return filtered.sum() / filtered.size
    }

    private fun getNextBeijingMidnightMs(): Long {
        val cal = Calendar.getInstance(beijingTz)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun sendWave(waveId: Int) {
        val waveIndex = waveId - 1
        try {
            val reqBody = "{\"is_retry\":false}"
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = buildHeaders(Request.Builder().url(unlockUrl).post(reqBody)).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .apply { timeZone = beijingTz }.format(Date())
                try {
                    val json = JSONObject(body)
                    val msg = json.optString("msg", "?")
                    val data = json.optJSONObject("data")
                    val result = data?.optInt("apply_result", -1) ?: -1
                    val meaning = getResultMeaning(result)
                    log("[Wave $waveId] $ts CST | HTTP ${resp.code} | $msg | result=$result $meaning")

                    if (result == 1) {
                        showSuccessNotification("Bootloader unlock slot secured successfully! (Wave $waveId at $ts CST)")
                    } else if (result == 2) {
                        showSuccessNotification("Bootloader unlock was already approved. You're all set!")
                    }

                    withContext(Dispatchers.Main) {
                        if (waveIndex in waves.indices) {
                            waves[waveIndex].state = when (result) {
                                1, 2 -> WaveState.SUCCESS
                                6 -> WaveState.FULL
                                else -> WaveState.ERROR
                            }
                            waves[waveIndex].resultText = "Res $result"
                        }
                    }
                } catch (e: Exception) {
                    log("[Wave $waveId] $ts CST | HTTP ${resp.code} | ${body.take(100)}...")
                    withContext(Dispatchers.Main) {
                        if (waveIndex in waves.indices) {
                            waves[waveIndex].state = WaveState.ERROR
                            waves[waveIndex].resultText = "HTTP ${resp.code}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("[Wave $waveId] ERROR: ${e.message}")
            withContext(Dispatchers.Main) {
                if (waveIndex in waves.indices) {
                    waves[waveIndex].state = WaveState.ERROR
                    waves[waveIndex].resultText = "Error"
                }
            }
        }
    }

    private fun getResultMeaning(code: Int): String = when (code) {
        1 -> "✅ APPROVED!"
        2 -> "✅ Already approved"
        6 -> "❌ Quota full - try tomorrow"
        else -> ""
    }
}

enum class WaveState { IDLE, SENDING, SUCCESS, FULL, ERROR }

class WaveStatus(
    val id: Int,
    val offset: String
) {
    var state by mutableStateOf(WaveState.IDLE)
    var resultText by mutableStateOf("Pending")
}

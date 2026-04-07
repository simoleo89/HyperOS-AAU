package com.xiaomi.unlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

data class HistoryEntry(val date: String, val result: String, val waves: String)

class UnlockViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("unlock_prefs", Context.MODE_PRIVATE)

    var cookie        by mutableStateOf(prefs.getString("saved_cookie", "") ?: "")
    var cookieValid   by mutableStateOf<Boolean?>(null)
    var isRunning     by mutableStateOf(false)
    var autoRetry     by mutableStateOf(false)
    var latencyMs     by mutableStateOf<Long?>(null)
    var ntpOffsetMs   by mutableStateOf<Long?>(null)
    var countdownText by mutableStateOf("Ready")

    val logs    = mutableStateListOf<String>()
    val waves   = mutableStateListOf<WaveStatus>()
    val history = mutableStateListOf<HistoryEntry>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10,  java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val userAgent  = "okhttp/4.12.0"
    private val unlockUrl  = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
    private val unlockHost = "sgp-api.buy.mi.com"
    private val ntpServer  = "pool.ntp.org"
    private val beijingTz  = TimeZone.getTimeZone("Asia/Shanghai")
    private val WAVE_COUNT = 8

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID      = "unlock_channel"
        const val NOTIF_FG_ID     = 1001
        const val NOTIF_RESULT_ID = 1002
    }

    init {
        createNotificationChannel()
        loadHistory()
        if (cookie.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { recheckCookieSilent() }
        }
    }

    // ── Cookie ────────────────────────────────────────────────────────────────

    fun saveCookie(value: String) {
        cookie = value
        prefs.edit().putString("saved_cookie", value).apply()
        cookieValid = null
        viewModelScope.launch(Dispatchers.IO) { recheckCookieSilent() }
    }

    private suspend fun recheckCookieSilent() {
        val valid = testCookie(silent = true)
        withContext(Dispatchers.Main) { cookieValid = valid }
    }

    // ── Avvio / Stop ──────────────────────────────────────────────────────────

    fun startProcess() {
        if (cookie.isBlank()) { log("[!] Cookie vuoto."); return }
        viewModelScope.launch(Dispatchers.IO) {
            isRunning = true
            runCycles()
            withContext(Dispatchers.Main) { isRunning = false }
        }
    }

    fun stopProcess() {
        isRunning = false
        releaseWakeLock()
        cancelForegroundNotif()
        log("[!] Interrotto dall'utente.")
        viewModelScope.launch(Dispatchers.Main) { countdownText = "Interrotto" }
    }

    // ── Ciclo principale (con auto-retry) ─────────────────────────────────────

    private suspend fun runCycles() {
        var attempt = 0
        do {
            attempt++
            log("=".repeat(42))
            log("▶ Tentativo #$attempt")

            val success = runOneCycle()

            if (!success && autoRetry && isRunning) {
                log("[🔁] Quota piena. Attendo prossimo midnight per retry...")
                showForegroundNotif("Retry auto attivo — aspetto midnight")
            }

        } while (!success && autoRetry && isRunning)

        releaseWakeLock()
        cancelForegroundNotif()
        withContext(Dispatchers.Main) {
            if (isRunning) countdownText = "Done"
            isRunning = false
        }
    }

    private suspend fun runOneCycle(): Boolean {
        // 1. Verifica cookie
        log("[Cookie] Verifica...")
        val valid = testCookie(silent = false)
        if (!valid) {
            log("[!] Cookie non valido.")
            withContext(Dispatchers.Main) { cookieValid = false; countdownText = "Cookie non valido" }
            sendResultNotif("❌ Cookie scaduto", "Reinserisci il cookie e riavvia.")
            isRunning = false
            return false
        }
        withContext(Dispatchers.Main) { cookieValid = true }
        log("[✓] Cookie valido.")

        // 2. NTP
        log("[NTP] Sincronizzazione...")
        val offset = getNtpOffset()
        withContext(Dispatchers.Main) { ntpOffsetMs = offset }
        log("[NTP] Offset: ${offset}ms")

        // 3. Target midnight Beijing
        val targetUtcMs = getNextBeijingMidnightMs()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'CST'", Locale.US).apply { timeZone = beijingTz }
        log("[Target] ${sdf.format(Date(targetUtcMs))}")

        // 4. WakeLock + notifica foreground
        acquireWakeLock()
        showForegroundNotif("In attesa midnight — ${sdf.format(Date(targetUtcMs))}")

        // 5. Re-check cookie ogni 30 min in background
        val recheckJob = viewModelScope.launch(Dispatchers.IO) {
            while (isRunning) {
                delay(30 * 60 * 1000L)
                if (!isRunning) break
                log("[Cookie] Re-check automatico...")
                val ok = testCookie(silent = true)
                withContext(Dispatchers.Main) { cookieValid = ok }
                if (!ok) {
                    log("[!] Cookie scaduto durante l'attesa!")
                    sendResultNotif("⚠️ Cookie scaduto", "Reinserisci il cookie.")
                    isRunning = false
                }
            }
        }

        // 6. Attesa fino a 23:59:50
        waitUntil(targetUtcMs - 10_000L, "Ping in")
        if (!isRunning) { recheckJob.cancel(); return false }

        // 7. Warmup TCP + misura latenza
        log("[Latency] Warmup TCP + misura latenza JIT...")
        withContext(Dispatchers.Main) { countdownText = "Pinging..." }
        val lat = measureLatencyWithWarmup()
        withContext(Dispatchers.Main) { latencyMs = lat }
        log("[Latency] ${lat}ms")

        // 8. Spread adattivo
        val spread = when {
            lat < 80  -> 15L
            lat < 150 -> 20L
            lat < 300 -> 30L
            else      -> 40L
        }
        log("[Waves] $WAVE_COUNT wave, spread: ±${spread}ms")

        // 9. Calcola offset wave
        val offsets = buildWaveOffsets(WAVE_COUNT, spread)

        withContext(Dispatchers.Main) {
            waves.clear()
            offsets.forEach { off ->
                waves.add(WaveStatus(waves.size + 1, "${if (off >= 0) "+" else ""}${off}ms"))
            }
        }

        // 10. Attesa fino al fuoco (Wave 1)
        val baseSendTime = targetUtcMs - lat
        val wave1Time    = baseSendTime + offsets[0]
        waitUntil(wave1Time, "Fire in")
        if (!isRunning) { recheckJob.cancel(); return false }

        withContext(Dispatchers.Main) { countdownText = "🔥 FIRING!" }
        showForegroundNotif("🔥 Invio richieste...")
        log(">>> FIRING <<<")

        // 11. Lancia wave in parallelo
        val stepMs = if (WAVE_COUNT > 1) (spread * 2) / (WAVE_COUNT - 1) else 0L
        val waveResults = mutableListOf<Int>()
        val jobs = offsets.mapIndexed { idx, _ ->
            viewModelScope.launch(Dispatchers.IO) {
                delay(idx * stepMs)
                withContext(Dispatchers.Main) {
                    if (idx in waves.indices) waves[idx].state = WaveState.SENDING
                }
                val res = sendWave(idx + 1)
                synchronized(waveResults) { waveResults.add(res) }
            }
        }
        jobs.forEach { it.join() }
        recheckJob.cancel()

        // 12. Analisi risultati
        val approved = waveResults.any { it == 1 || it == 2 }
        val full     = waveResults.all { it == 6 }
        val summary  = waveResults.joinToString(" ") { "[$it]" }
        val dateStr  = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = beijingTz }.format(Date())

        return if (approved) {
            log("[🎉] SLOT OTTENUTO!")
            addHistory(dateStr, "✅ APPROVATO", summary)
            sendResultNotif("🎉 Bootloader Approvato!", "Vai in Impostazioni Xiaomi per sbloccare.")
            withContext(Dispatchers.Main) { countdownText = "✅ Approvato!" }
            true
        } else if (full) {
            log("[❌] Quota piena.")
            addHistory(dateStr, "❌ QUOTA PIENA", summary)
            sendResultNotif("❌ Quota piena", "Riprovo domani a mezzanotte.")
            withContext(Dispatchers.Main) { countdownText = "Quota piena" }
            false
        } else {
            log("[⚠️] Risultato incerto.")
            addHistory(dateStr, "⚠️ INCERTO", summary)
            withContext(Dispatchers.Main) { countdownText = "Incerto" }
            false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildWaveOffsets(count: Int, spread: Long): List<Long> {
        if (count == 1) return listOf(0L)
        val step = (spread * 2) / (count - 1)
        return (0 until count).map { i -> -spread + i * step }
    }

    private suspend fun waitUntil(targetMs: Long, label: String) {
        while (isRunning) {
            val rem = targetMs - (System.currentTimeMillis() + (ntpOffsetMs ?: 0L))
            if (rem <= 0) break
            val h = rem / 3_600_000
            val m = (rem % 3_600_000) / 60_000
            val s = (rem % 60_000) / 1_000
            val text = when {
                rem > 60_000 -> "$label %02dh %02dm %02ds".format(h, m, s)
                rem > 3_000  -> "$label %.2fs".format(rem / 1000.0)
                else         -> "$label %.3fs".format(rem / 1000.0)
            }
            withContext(Dispatchers.Main) { countdownText = text }
            showForegroundNotif(text)
            delay(if (rem > 60_000) 1000L else if (rem > 3_000) 50L else minOf(rem, 10L))
        }
    }

    private fun buildHeaders(r: Request.Builder): Request.Builder = r
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("Connection", "Keep-Alive")
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Cookie", cookie)
        .header("Host", unlockHost)
        .header("User-Agent", userAgent)

    private fun testCookie(silent: Boolean = false): Boolean {
        return try {
            val body = "{\"is_retry\":false}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val req  = buildHeaders(Request.Builder().url(unlockUrl).post(body)).build()
            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val msg  = json.optString("msg", "")
            val res  = json.optJSONObject("data")?.optInt("apply_result", -1) ?: -1
            if (!silent) log("[Cookie] HTTP ${resp.code} | msg=$msg | res=$res ${getResultMeaning(res)}")
            msg != "need login"
        } catch (e: Exception) {
            if (!silent) log("[Cookie] Errore: ${e.message}")
            false
        }
    }

    private fun getNtpOffset(): Long {
        return try {
            val c = NTPUDPClient()
            c.setDefaultTimeout(Duration.ofMillis(5000))
            c.open()
            val info = c.getTime(InetAddress.getByName(ntpServer))
            info.computeDetails()
            c.close()
            info.offset ?: 0L
        } catch (e: Exception) {
            log("[NTP] Errore: ${e.message}")
            0L
        }
    }

    private fun measureLatencyWithWarmup(): Long {
        // Pre-riscaldamento connessione TCP
        var warmup: Socket? = null
        try { warmup = Socket(unlockHost, 443).also { it.soTimeout = 5000 } } catch (_: Exception) {}

        val times = mutableListOf<Long>()
        repeat(6) {
            try {
                val t0  = System.currentTimeMillis()
                val req = Request.Builder().url("https://$unlockHost").head().build()
                client.newBuilder()
                    .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(req).execute().close()
                times.add(System.currentTimeMillis() - t0)
            } catch (_: Exception) {}
        }
        try { warmup?.close() } catch (_: Exception) {}

        return if (times.isNotEmpty()) {
            val sorted = times.sorted().dropLast(1) // scarta il peggiore
            sorted.sum() / sorted.size
        } else {
            log("[Latency] Default 300ms")
            300L
        }
    }

    private suspend fun sendWave(waveId: Int): Int {
        val idx = waveId - 1
        return try {
            val body = "{\"is_retry\":false}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val req  = buildHeaders(Request.Builder().url(unlockUrl).post(body)).build()
            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val msg  = json.optString("msg", "?")
            val res  = json.optJSONObject("data")?.optInt("apply_result", -1) ?: -1
            val ts   = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = beijingTz }.format(Date())
            log("[Wave $waveId] $ts | HTTP ${resp.code} | $msg | $res ${getResultMeaning(res)}")
            withContext(Dispatchers.Main) {
                if (idx in waves.indices) {
                    waves[idx].state = when {
                        res == 1 || res == 2 -> WaveState.SUCCESS
                        res == 6             -> WaveState.FULL
                        else                 -> WaveState.ERROR
                    }
                    waves[idx].resultText = "Res $res"
                }
            }
            res
        } catch (e: Exception) {
            log("[Wave $waveId] ERRORE: ${e.message}")
            withContext(Dispatchers.Main) {
                if (idx in waves.indices) {
                    waves[idx].state = WaveState.ERROR
                    waves[idx].resultText = "Err"
                }
            }
            -1
        }
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

    private fun getResultMeaning(code: Int) = when (code) {
        1    -> "✅ APPROVATO!"
        2    -> "✅ Già approvato"
        6    -> "❌ Quota piena"
        else -> ""
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XiaomiUnlock::WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
        log("[🔒] WakeLock attivo — CPU rimane sveglia.")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Notifiche ─────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Xiaomi Unlock", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showForegroundNotif(text: String) {
        val ctx = getApplication<Application>()
        val pi  = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Xiaomi Unlock — In esecuzione")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_FG_ID, notif)
    }

    private fun cancelForegroundNotif() {
        val nm = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_FG_ID)
    }

    private fun sendResultNotif(title: String, text: String) {
        val ctx = getApplication<Application>()
        val pi  = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_RESULT_ID, notif)
    }

    // ── Storico ───────────────────────────────────────────────────────────────

    private fun addHistory(date: String, result: String, wavesSummary: String) {
        val entry = HistoryEntry(date, result, wavesSummary)
        viewModelScope.launch(Dispatchers.Main) { history.add(0, entry) }
        val arr = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val obj = JSONObject().apply { put("date", date); put("result", result); put("waves", wavesSummary) }
        arr.put(obj)
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - 20) until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString("history", trimmed.toString()).apply()
    }

    private fun loadHistory() {
        val arr = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            history.add(HistoryEntry(o.optString("date"), o.optString("result"), o.optString("waves")))
        }
    }

    private fun log(message: String) {
        viewModelScope.launch(Dispatchers.Main) { logs.add(message) }
    }
}

enum class WaveState { IDLE, SENDING, SUCCESS, FULL, ERROR }

class WaveStatus(val id: Int, val offset: String) {
    var state      by mutableStateOf(WaveState.IDLE)
    var resultText by mutableStateOf("Pending")
}

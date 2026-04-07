package com.xiaomi.unlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

// ── Modello storico ──────────────────────────────────────────────────────────
data class HistoryEntry(
    val date: String,
    val result: String,
    val waves: String
)

class UnlockViewModel(application: Application) : AndroidViewModel(application) {

    // ── Prefs ────────────────────────────────────────────────────────────────
    private val prefs: SharedPreferences =
        application.getSharedPreferences("unlock_prefs", Context.MODE_PRIVATE)

    // ── UI State ─────────────────────────────────────────────────────────────
    var cookie by mutableStateOf(prefs.getString("saved_cookie", "") ?: "")
    var cookieValid by mutableStateOf<Boolean?>(null)   // null=unknown, true=ok, false=expired
    var isRunning by mutableStateOf(false)
    var latencyMs by mutableStateOf<Long?>(null)
    var ntpOffsetMs by mutableStateOf<Long?>(null)
    var countdownText by mutableStateOf("Ready")
    val logs = mutableStateListOf<String>()
    val waves = mutableStateListOf<WaveStatus>()
    val history = mutableStateListOf<HistoryEntry>()

    // ── Internals ────────────────────────────────────────────────────────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val userAgent   = "okhttp/4.12.0"
    private val unlockUrl   = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
    private val unlockHost  = "sgp-api.buy.mi.com"
    private val ntpServer   = "pool.ntp.org"
    private val beijingTz   = TimeZone.getTimeZone("Asia/Shanghai")

    // Numero di wave e spread adattivo (calcolati in base alla latenza)
    private val WAVE_COUNT  = 8
    private var waveSpreadMs = 20L   // default, viene ricalcolato

    init {
        createNotificationChannel()
        loadHistory()
        // Re-check cookie salvato all'avvio
        if (cookie.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { recheckCookie() }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Salvataggio / caricamento cookie
    // ────────────────────────────────────────────────────────────────────────

    fun saveCookie(value: String) {
        cookie = value
        prefs.edit().putString("saved_cookie", value).apply()
        cookieValid = null
        viewModelScope.launch(Dispatchers.IO) { recheckCookie() }
    }

    private suspend fun recheckCookie() {
        val valid = testCookie(silent = true)
        withContext(Dispatchers.Main) { cookieValid = valid }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Processo principale
    // ────────────────────────────────────────────────────────────────────────

    fun startProcess() {
        if (cookie.isBlank()) { log("[!] Cookie vuoto."); return }

        viewModelScope.launch(Dispatchers.IO) {
            isRunning = true
            runUnlockCycle()
        }
    }

    fun stopProcess() {
        isRunning = false
        log("[!] Processo interrotto dall'utente.")
        stopForegroundService()
    }

    /**
     * Ciclo principale — con retry automatico alla mezzanotte successiva
     * se la quota è piena (result=6).
     */
    private suspend fun runUnlockCycle() {
        var attempt = 0
        while (isRunning) {
            attempt++
            log("=" * 42)
            log("▶ Tentativo #$attempt — Xiaomi BL Unlock Automator")

            // 1. Verifica cookie
            log("[Cookie] Verifica in corso...")
            val valid = testCookie(silent = false)
            if (!valid) {
                log("[!] Cookie scaduto o non valido. Inseriscine uno nuovo.")
                withContext(Dispatchers.Main) { cookieValid = false }
                isRunning = false
                stopForegroundService()
                sendNotification("❌ Cookie scaduto", "Inserisci un nuovo cookie per continuare.")
                return
            }
            withContext(Dispatchers.Main) { cookieValid = true }
            log("[✓] Cookie valido.")

            // 2. NTP sync
            log("[NTP] Sincronizzazione orologio...")
            ntpOffsetMs = getNtpOffset()
            log("[NTP] Offset: ${ntpOffsetMs}ms")

            // 3. Target = prossima mezzanotte Beijing
            val targetUtcMs = getNextBeijingMidnightMs()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'CST'", Locale.US).apply { timeZone = beijingTz }
            log("[Target] ${sdf.format(Date(targetUtcMs))}")

            // 4. Avvia ForegroundService per wakelock
            startForegroundService("Countdown in corso...")

            // 5. Re-check cookie ogni 30 min durante l'attesa
            val cookieRecheckJob = viewModelScope.launch(Dispatchers.IO) {
                while (isRunning) {
                    delay(30 * 60 * 1000L)
                    if (!isRunning) break
                    log("[Cookie] Re-check automatico (30 min)...")
                    val stillValid = testCookie(silent = true)
                    withContext(Dispatchers.Main) { cookieValid = stillValid }
                    if (!stillValid) {
                        log("[!] Cookie scaduto durante l'attesa! Processo fermato.")
                        sendNotification("⚠️ Cookie scaduto", "Reinserisci il cookie prima della mezzanotte.")
                        isRunning = false
                    }
                }
            }

            // 6. Attendi fino a 23:59:50 per misurare latenza JIT
            val targetPingMs = targetUtcMs - 10_000L
            waitUntil(targetPingMs, "Ping in")
            if (!isRunning) { cookieRecheckJob.cancel(); break }

            // 7. Misura latenza JIT + pre-riscaldamento TCP
            log("[Latency] Misura JIT + pre-riscaldamento TCP...")
            withContext(Dispatchers.Main) { countdownText = "Pinging..." }
            val lat = measureLatencyWithWarmup()
            latencyMs = lat
            log("[Latency] Latenza finale: ${lat}ms")

            // 8. Spread adattivo: più la latenza è alta, più le wave sono distanziate
            waveSpreadMs = when {
                lat < 80  -> 15L
                lat < 150 -> 20L
                lat < 300 -> 30L
                else      -> 40L
            }
            log("[Waves] $WAVE_COUNT wave, spread adattivo: ${waveSpreadMs}ms")

            // 9. Calcola timing wave
            val baseSendMs = targetUtcMs - lat
            val firstWaveMs = baseSendMs - (waveSpreadMs * (WAVE_COUNT / 2))

            // Prepara UI wave
            withContext(Dispatchers.Main) {
                waves.clear()
                for (i in 0 until WAVE_COUNT) {
                    val offsetMs = -((WAVE_COUNT / 2) * waveSpreadMs) + (i * waveSpreadMs)
                    val sign = if (offsetMs >= 0) "+" else ""
                    waves.add(WaveStatus(i + 1, "${sign}${offsetMs}ms"))
                }
            }

            // 10. Attendi wave 1
            waitUntil(firstWaveMs, "Fire in")
            if (!isRunning) { cookieRecheckJob.cancel(); break }

            withContext(Dispatchers.Main) { countdownText = "🔥 FIRING" }
            log("🔥 FUOCO!")
            cookieRecheckJob.cancel()

            // 11. Lancia tutte le wave
            val waveResults = Array(WAVE_COUNT) { -99 }
            val waveJobs = (0 until WAVE_COUNT).map { i ->
                viewModelScope.launch(Dispatchers.IO) {
                    if (i > 0) delay(i * waveSpreadMs)
                    val offsetMs = -((WAVE_COUNT / 2) * waveSpreadMs) + (i * waveSpreadMs)
                    val sign = if (offsetMs >= 0) "+" else ""
                    val ts = tsNow()
                    log("[Wave ${i+1}] $ts CST (${sign}${offsetMs}ms)")
                    withContext(Dispatchers.Main) {
                        if (i in waves.indices) waves[i].state = WaveState.SENDING
                    }
                    waveResults[i] = sendWave(i + 1)
                }
            }
            waveJobs.forEach { it.join() }

            delay(1500)

            // 12. Analisi risultati
            val approved = waveResults.any { it == 1 || it == 2 }
            val full     = waveResults.all { it == 6 }
            val summary  = waveResults.joinToString(" ") { "[$it]" }

            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.IT)
                .apply { timeZone = beijingTz }.format(Date())

            if (approved) {
                val msg = "✅ SLOT OTTENUTO! Vai nelle impostazioni Xiaomi per sbloccare."
                log("[Risultato] $msg")
                log("[Risultato] Wave: $summary")
                sendNotification("✅ Bootloader APPROVATO!", msg)
                addHistory(dateStr, "✅ APPROVATO", summary)
                isRunning = false
                withContext(Dispatchers.Main) { countdownText = "✅ Approvato!" }
                stopForegroundService()
                return
            } else if (full) {
                log("[Risultato] ❌ Quota piena (result=6). Riprovo alla prossima mezzanotte...")
                sendNotification("❌ Quota piena", "Riprovo automaticamente alla prossima mezzanotte Beijing.")
                addHistory(dateStr, "❌ QUOTA PIENA", summary)
                withContext(Dispatchers.Main) { countdownText = "Retry domani..." }
                // Loop: isRunning rimane true → riparte il ciclo
            } else {
                log("[Risultato] ⚠️ Esito incerto. Wave: $summary")
                sendNotification("⚠️ Esito incerto", "Controlla il log per i dettagli.")
                addHistory(dateStr, "⚠️ INCERTO", summary)
                isRunning = false
                withContext(Dispatchers.Main) { countdownText = "Vedi log" }
                stopForegroundService()
                return
            }
        }

        stopForegroundService()
        log("[Fine] Processo completato.")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun log(message: String) {
        viewModelScope.launch(Dispatchers.Main) { logs.add(message) }
    }

    private fun tsNow(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            .apply { timeZone = beijingTz }
            .format(Date(System.currentTimeMillis() + (ntpOffsetMs ?: 0L)))

    private suspend fun waitUntil(targetMs: Long, label: String) {
        while (isRunning) {
            val now = System.currentTimeMillis() + (ntpOffsetMs ?: 0L)
            val rem = targetMs - now
            if (rem <= 0) break
            withContext(Dispatchers.Main) {
                countdownText = when {
                    rem > 60_000 -> {
                        val h = rem / 3_600_000
                        val m = (rem % 3_600_000) / 60_000
                        val s = (rem % 60_000) / 1000
                        "$label %02dh %02dm %02ds".format(h, m, s)
                    }
                    rem > 3000   -> "$label %.2fs".format(rem / 1000.0)
                    else         -> "$label %.3fs".format(rem / 1000.0)
                }
                // Aggiorna testo notifica
                updateForegroundNotification(countdownText)
            }
            delay(if (rem > 60_000) 1000L else if (rem > 3000) 50L else minOf(rem, 10L))
        }
    }

    private fun buildHeaders(reqBuilder: Request.Builder): Request.Builder =
        reqBuilder
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
            if (!silent) log("[Cookie] HTTP ${resp.code} | msg=$msg | result=$res ${getResultMeaning(res)}")
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
            log("[NTP] Errore: ${e.message} — offset 0")
            0L
        }
    }

    /**
     * Misura latenza + pre-riscaldamento TCP (apre socket verso il server
     * così la connessione è già pronta al momento del fuoco).
     */
    private fun measureLatencyWithWarmup(): Long {
        // Pre-riscaldamento: apri socket e tienilo aperto
        val warmupSocket = try {
            Socket(unlockHost, 443).also { it.soTimeout = 5000 }
        } catch (e: Exception) { null }

        val times = mutableListOf<Long>()
        repeat(6) {
            try {
                val t0 = System.currentTimeMillis()
                val req = Request.Builder().url("https://$unlockHost").head().build()
                client.newBuilder()
                    .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute().close()
                times.add(System.currentTimeMillis() - t0)
            } catch (_: Exception) {}
        }

        // Chiudi socket warmup dopo la misurazione
        try { warmupSocket?.close() } catch (_: Exception) {}

        return if (times.isNotEmpty()) {
            // Scarta il valore più alto (outlier) e media il resto
            val sorted = times.sorted().dropLast(1)
            sorted.sum() / sorted.size
        } else {
            log("[Latency] Impossibile misurare — default 300ms")
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
            log("[Wave $waveId] ${tsNow()} | HTTP ${resp.code} | $msg | $res ${getResultMeaning(res)}")
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

    private operator fun String.times(n: Int) = this.repeat(max(0, n))

    // ────────────────────────────────────────────────────────────────────────
    // Storico
    // ────────────────────────────────────────────────────────────────────────

    private fun addHistory(date: String, result: String, waves: String) {
        val entry = HistoryEntry(date, result, waves)
        viewModelScope.launch(Dispatchers.Main) { history.add(0, entry) }
        // Salva in prefs come JSON array (max 20 voci)
        val arr = try {
            JSONArray(prefs.getString("history", "[]"))
        } catch (_: Exception) { JSONArray() }
        val obj = JSONObject().apply {
            put("date", date); put("result", result); put("waves", waves)
        }
        arr.put(obj)
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 20)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString("history", trimmed.toString()).apply()
    }

    private fun loadHistory() {
        val arr = try {
            JSONArray(prefs.getString("history", "[]"))
        } catch (_: Exception) { JSONArray() }
        val list = mutableListOf<HistoryEntry>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            list.add(HistoryEntry(o.optString("date"), o.optString("result"), o.optString("waves")))
        }
        history.addAll(list)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Notifiche
    // ────────────────────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID      = "unlock_channel"
        const val NOTIF_ID        = 1001
        const val NOTIF_RESULT_ID = 1002
    }

    private fun createNotificationChannel() {
        val nm = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Xiaomi Unlock", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun sendNotification(title: String, text: String) {
        val ctx = getApplication<Application>()
        val intent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_RESULT_ID, notif)
    }

    private fun startForegroundService(text: String) {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(
            Intent(ctx, UnlockForegroundService::class.java).apply {
                putExtra("text", text)
            }
        )
    }

    private fun updateForegroundNotification(text: String) {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, UnlockForegroundService::class.java).apply {
                putExtra("text", text)
                putExtra("update", true)
            }
        )
    }

    private fun stopForegroundService() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, UnlockForegroundService::class.java))
    }
}

enum class WaveState { IDLE, SENDING, SUCCESS, FULL, ERROR }

class WaveStatus(val id: Int, val offset: String) {
    var state      by mutableStateOf(WaveState.IDLE)
    var resultText by mutableStateOf("Pending")
}

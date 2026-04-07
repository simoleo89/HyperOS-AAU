package com.xiaomi.unlock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val OrangeMain    = Color(0xFFFF6900)
val DarkBackground = Color(0xFF141414)
val SurfaceColor  = Color(0xFF1E1E1E)
val TextGray      = Color(0xFFAAAAAA)
val GreenOk       = Color(0xFF67C23A)
val RedErr        = Color(0xFFF56C6C)

class MainActivity : ComponentActivity() {
    private val viewModel: UnlockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary       = OrangeMain,
                    background    = DarkBackground,
                    surface       = SurfaceColor,
                    onPrimary     = Color.White,
                    onBackground  = Color.White,
                    onSurface     = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UnlockScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun UnlockScreen(viewModel: UnlockViewModel) {
    val listState  = rememberLazyListState()
    val context    = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }

    val cookieLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val c = result.data?.getStringExtra(CookieLoginActivity.EXTRA_COOKIE)
            if (!c.isNullOrBlank()) {
                viewModel.saveCookie(c)
                Toast.makeText(context, "✅ Cookie estratto e salvato!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "⚠️ Cookie non trovato, riprova.", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) listState.animateScrollToItem(viewModel.logs.size - 1)
    }

    if (showHistory) {
        HistoryScreen(viewModel.history) { showHistory = false }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Xiaomi Unlock Automator",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OrangeMain,
                modifier = Modifier.weight(1f).padding(top = 20.dp, bottom = 20.dp)
            )
            TextButton(onClick = { showHistory = true }) {
                Text("📋 Log", color = TextGray, fontSize = 12.sp)
            }
        }

        // Status cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatusCard("Latency",    viewModel.latencyMs?.let { "${it}ms" } ?: "--")
            StatusCard("NTP Offset", viewModel.ntpOffsetMs?.let { "${it}ms" } ?: "--")
        }

        Spacer(Modifier.height(16.dp))

        // Countdown
        Text(
            viewModel.countdownText,
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = if (viewModel.isRunning) OrangeMain else Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        // Cookie input con indicatore validità
        val cookieBorder = when (viewModel.cookieValid) {
            true  -> GreenOk
            false -> RedErr
            null  -> TextGray
        }
        val cookieLabel = when (viewModel.cookieValid) {
            true  -> "Cookie ✅ Valido"
            false -> "Cookie ❌ Scaduto"
            null  -> "Cookie String"
        }
        OutlinedTextField(
            value = viewModel.cookie,
            onValueChange = { viewModel.saveCookie(it) },
            label = { Text(cookieLabel) },
            placeholder = { Text("Incolla o usa il login in-app...") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.isRunning,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = OrangeMain,
                unfocusedBorderColor = cookieBorder,
                focusedLabelColor    = OrangeMain,
                unfocusedLabelColor  = cookieBorder,
                cursorColor          = OrangeMain
            )
        )

        Spacer(Modifier.height(8.dp))

        // Pulsante login in-app
        OutlinedButton(
            onClick = { cookieLauncher.launch(Intent(context, CookieLoginActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            enabled = !viewModel.isRunning,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeMain),
            border = BorderStroke(1.dp, OrangeMain)
        ) {
            Text("🔑  Ottieni Cookie (Login in-app)", fontSize = 14.sp)
        }

        Spacer(Modifier.height(10.dp))

        // Start / Stop
        if (!viewModel.isRunning) {
            Button(
                onClick = { viewModel.startProcess() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeMain)
            ) {
                Text("▶  Avvia Processo", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { viewModel.stopProcess() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RedErr)
            ) {
                Text("⏹  Interrompi", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Wave indicators
        if (viewModel.waves.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                viewModel.waves.forEach { WaveCard(it) }
            }
        }

        // Log console
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(viewModel.logs) { msg ->
                    Text(
                        msg,
                        color = when {
                            msg.contains("✅") -> GreenOk
                            msg.contains("❌") || msg.contains("[!]") -> RedErr
                            msg.contains("🔥") -> OrangeMain
                            else -> Color(0xFF00FF00)
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(history: List<HistoryEntry>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Indietro", color = OrangeMain) }
            Text("Storico Tentativi", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 20.dp))
        }
        if (history.isEmpty()) {
            Text("Nessun tentativo ancora.", color = TextGray, modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                textAlign = TextAlign.Center)
        } else {
            LazyColumn {
                items(history) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(entry.date, fontSize = 12.sp, color = TextGray)
                                Text(entry.result, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = when {
                                        entry.result.contains("✅") -> GreenOk
                                        entry.result.contains("❌") -> RedErr
                                        else -> Color(0xFFE6A23C)
                                    })
                            }
                            Text("Wave: ${entry.waves}", fontSize = 10.sp,
                                color = TextGray, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier.width(150.dp).height(72.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 11.sp, color = TextGray)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun WaveCard(wave: WaveStatus) {
    val color = when (wave.state) {
        WaveState.IDLE    -> SurfaceColor
        WaveState.SENDING -> Color(0xFFE6A23C)
        WaveState.SUCCESS -> GreenOk
        WaveState.FULL    -> RedErr
        WaveState.ERROR   -> Color(0xFF909399)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.width(52.dp).height(58.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(3.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val tc = if (wave.state == WaveState.IDLE) TextGray else Color.White
            Text(wave.offset, fontSize = 9.sp, color = tc, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            Text(wave.resultText, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                color = tc, textAlign = TextAlign.Center)
        }
    }
}

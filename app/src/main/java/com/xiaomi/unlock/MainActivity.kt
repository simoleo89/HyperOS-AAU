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

val OrangeMain = Color(0xFFFF6900)
val DarkBackground = Color(0xFF141414)
val SurfaceColor = Color(0xFF1E1E1E)
val TextGray = Color(0xFFAAAAAA)

class MainActivity : ComponentActivity() {
    private val viewModel: UnlockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = OrangeMain,
                    background = DarkBackground,
                    surface = SurfaceColor,
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnlockScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun UnlockScreen(viewModel: UnlockViewModel) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val cookieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookie = result.data?.getStringExtra(CookieLoginActivity.EXTRA_COOKIE)
            if (!cookie.isNullOrBlank()) {
                viewModel.cookie = cookie
                Toast.makeText(context, "✅ Cookie estratto con successo!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "⚠️ Cookie non trovato, riprova il login.", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Xiaomi Unlock Automator",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = OrangeMain,
            modifier = Modifier.padding(bottom = 24.dp, top = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatusCard(title = "Latency", value = viewModel.latencyMs?.let { "${it}ms" } ?: "--")
            StatusCard(title = "NTP Offset", value = viewModel.ntpOffsetMs?.let { "${it}ms" } ?: "--")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = viewModel.countdownText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (viewModel.isRunning) OrangeMain else Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 24.dp)
                .fillMaxWidth()
        )

        OutlinedTextField(
            value = viewModel.cookie,
            onValueChange = { viewModel.cookie = it },
            label = { Text("Cookie String") },
            placeholder = { Text("Paste Cookie Here...") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.isRunning,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OrangeMain,
                focusedLabelColor = OrangeMain,
                cursorColor = OrangeMain
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                cookieLauncher.launch(Intent(context, CookieLoginActivity::class.java))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !viewModel.isRunning,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeMain),
            border = BorderStroke(1.dp, OrangeMain)
        ) {
            Text("🔑  Ottieni Cookie (Login in-app)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!viewModel.isRunning) {
            Button(
                onClick = { viewModel.startProcess() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeMain)
            ) {
                Text("Verify & Start Process", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { viewModel.stopProcess() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("Abort Process", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (viewModel.waves.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                viewModel.waves.forEach { wave -> WaveCard(wave) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(viewModel.logs) { logMsg ->
                    Text(
                        text = logMsg,
                        color = Color(0xFF00FF00),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .width(150.dp)
            .height(80.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontSize = 12.sp, color = TextGray)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun WaveCard(wave: WaveStatus) {
    val color = when (wave.state) {
        WaveState.IDLE -> SurfaceColor
        WaveState.SENDING -> Color(0xFFE6A23C)
        WaveState.SUCCESS -> Color(0xFF67C23A)
        WaveState.FULL -> Color(0xFFF56C6C)
        WaveState.ERROR -> Color(0xFF909399)
    }
    val textColor = if (wave.state == WaveState.IDLE) TextGray else Color.White

    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .width(80.dp)
            .height(65.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = wave.offset, fontSize = 11.sp, color = textColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = wave.resultText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
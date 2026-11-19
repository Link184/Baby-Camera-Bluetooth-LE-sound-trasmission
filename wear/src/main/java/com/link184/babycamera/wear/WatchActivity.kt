package com.link184.babycamera.wear

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import com.link184.babycamera.watch.WatchProtocol
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Shows what the parent unit on the phone hears, and lets the parent dismiss its crying alert. */
class WatchActivity : ComponentActivity() {
    private lateinit var phone: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = PhoneLink(this, lifecycleScope)
        setContent {
            MaterialTheme { WatchScreen(phone) }
        }
    }

    override fun onResume() {
        super.onResume()
        phone.resume()
    }

    override fun onPause() {
        phone.pause()
        super.onPause()
    }
}

@Composable
private fun WatchScreen(phone: PhoneLink) {
    // Ticks so "nothing from the phone for a while" shows without any new message arriving.
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val heardRecently = phone.lastHeardAt?.let { now - it < STALE_MS } == true
    val state = phone.state

    when {
        phone.phoneInReach == false -> Message("Phone out of reach", "Keep your phone closer to the watch.")
        !heardRecently -> Message("Waiting for the phone", "Open BabyCamera on your phone.")
        state == null || !state.listening -> Message(state?.status ?: "Not listening", "Start the parent unit on your phone.")
        else -> Monitor(state, phone.level, onDismiss = phone::dismissAlert)
    }
}

@Composable
private fun Monitor(state: WatchProtocol.State, level: Float?, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val loud = level != null && level >= state.threshold
    Box(
        Modifier.fillMaxSize().background(if (state.crying) colors.errorContainer else Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { level ?: 0f },
            modifier = Modifier.fillMaxSize().padding(4.dp),
            colors = ProgressIndicatorDefaults.colors(indicatorColor = if (loud) colors.error else colors.primary),
            strokeWidth = 10.dp,
        )
        Column(
            Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.crying) {
                Text("Crying!", style = MaterialTheme.typography.titleLarge, color = colors.onErrorContainer)
                Button(onClick = onDismiss) { Text("Dismiss") }
            } else {
                Text(state.status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    level?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    "Alerts above ${(state.threshold * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Message(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/** Level messages come 4 times a second; this long without one means the phone app isn't sending. */
private const val STALE_MS = 3_000L

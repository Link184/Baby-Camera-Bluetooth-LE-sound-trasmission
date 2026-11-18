package com.link184.babycamera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.PeerLink
import com.link184.babycamera.link.Role

@Composable
fun App(link: PeerLink) {
    val scope = rememberCoroutineScope()
    val controller = remember(link) { PrototypeController(link, scope) }
    val state by controller.state.collectAsState()
    val rssi by controller.rssi.collectAsState()
    var role by remember { mutableStateOf(Role.Host) }
    var longRange by remember { mutableStateOf(false) }
    val running = controller.session != null

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("BabyCamera - Bluetooth LE link test", style = MaterialTheme.typography.titleMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == Role.Host,
                        onClick = { role = Role.Host },
                        enabled = !running,
                        label = { Text("Host (advertise)") },
                    )
                    FilterChip(
                        selected = role == Role.Join,
                        onClick = { role = Role.Join },
                        enabled = !running,
                        label = { Text("Join (scan)") },
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = longRange && link.supportsLongRange,
                        onCheckedChange = { longRange = it },
                        enabled = !running && link.supportsLongRange,
                    )
                    Text(
                        if (link.supportsLongRange) {
                            "Long range (LE Coded) - both phones must be Android with it on"
                        } else {
                            "Long range not available on this phone"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (running) {
                    Button(onClick = controller::stop) { Text("Stop") }
                } else {
                    Button(onClick = { controller.start(role, longRange && link.supportsLongRange) }) { Text("Start") }
                }

                StatusLine(state, controller.session != null)
                if (state is LinkState.Connected) {
                    Metrics(controller.rttMs, rssi, controller.peerRssi, controller.drops)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = controller::runBulkTest, enabled = !controller.bulkRunning) {
                            Text(if (controller.bulkRunning) "Measuring..." else "Throughput test")
                        }
                        OutlinedButton(onClick = controller::toggleStream) {
                            Text(if (controller.streaming) "Stop stream" else "Audio-like stream")
                        }
                    }
                } else if (controller.drops > 0) {
                    Text("Drops so far: ${controller.drops}", style = MaterialTheme.typography.bodyMedium)
                }
                controller.streamReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                HorizontalDivider()
                LogView(controller.logLines, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusLine(state: LinkState, reconnecting: Boolean) {
    val text = when (state) {
        LinkState.Idle -> "Idle"
        is LinkState.Working -> state.step
        is LinkState.Connected -> "Connected to ${state.peer}"
        is LinkState.Failed -> if (reconnecting) "Lost: ${state.reason} - retrying" else "Failed: ${state.reason}"
    }
    val color = when (state) {
        is LinkState.Failed -> MaterialTheme.colorScheme.error
        is LinkState.Connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Metrics(rttMs: Double?, rssi: Int?, peerRssi: Int?, drops: Int) {
    val parts = buildList {
        rttMs?.let { add("round trip ${(it * 10).toLong() / 10.0} ms") }
        (rssi ?: peerRssi)?.let { add("signal $it dBm") }
        add("drops $drops")
    }
    Text(parts.joinToString("  -  "), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    SelectionContainer(modifier.fillMaxWidth()) {
        LazyColumn(state = listState) {
            items(lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

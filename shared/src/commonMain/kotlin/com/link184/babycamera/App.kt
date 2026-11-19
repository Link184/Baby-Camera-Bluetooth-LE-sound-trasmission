package com.link184.babycamera

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.link184.babycamera.link.LinkState

@Composable
fun App(controller: MonitorController) {
    var showLinkTest by remember { mutableStateOf(false) }
    // Follows the phone's dark mode setting, and switches live when it changes.
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showLinkTest) {
                    LinkTestScreen(controller, onBack = { showLinkTest = false })
                } else {
                    MonitorScreen(controller, onLinkTest = { showLinkTest = true })
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MonitorScreen(controller: MonitorController, onLinkTest: () -> Unit) {
    val state by controller.state.collectAsState()
    var chosenRole by remember { mutableStateOf(MonitorRole.Parent) }
    val running = controller.session
    val role = running?.role ?: chosenRole

    Text("BabyCamera", style = MaterialTheme.typography.headlineSmall)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoleCard(
            title = "Baby",
            description = "Stays next to the baby and listens",
            selected = role == MonitorRole.Baby,
            enabled = running == null,
            onClick = { chosenRole = MonitorRole.Baby },
            modifier = Modifier.weight(1f),
        )
        RoleCard(
            title = "Parent",
            description = "Plays the sound and alerts you",
            selected = role == MonitorRole.Parent,
            enabled = running == null,
            onClick = { chosenRole = MonitorRole.Parent },
            modifier = Modifier.weight(1f),
        )
    }

    if (running == null) {
        Button(onClick = { controller.start(chosenRole) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (chosenRole == MonitorRole.Baby) "Start listening to the baby" else "Start receiving")
        }
        controller.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } else {
        OutlinedButton(onClick = controller::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        StatusLine(state, reconnecting = true)
        Text(
            "Keeps working with the screen off or another app open.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (controller.crying) CryingBanner(onDismiss = controller::dismissAlert)

    if (running != null) {
        LevelMeter(controller.level, threshold = if (role == MonitorRole.Parent) controller.threshold else null)
    }
    if (role == MonitorRole.Parent) {
        Text(
            "Alert me when the sound stays above ${percent(controller.threshold)} for about a second",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(value = controller.threshold, onValueChange = controller::updateThreshold)
    }

    Spacer(Modifier.weight(1f))
    TextButton(onClick = onLinkTest, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Link test")
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CryingBanner(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The baby is crying",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** Live sound level, with a mark at [threshold] when there is one. */
@Composable
private fun LevelMeter(level: Float?, threshold: Float?) {
    val value = level ?: 0f
    val loud = threshold != null && value >= threshold
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (level == null) "Sound level: no sound yet" else "Sound level: ${percent(value)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.fillMaxWidth().height(14.dp)) {
            LinearProgressIndicator(
                progress = { value },
                color = if (loud) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.matchParentSize(),
            )
            if (threshold != null) {
                val mark = threshold.coerceIn(0.01f, 0.99f)
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.weight(mark))
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
                    Spacer(Modifier.weight(1f - mark))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LinkTestScreen(controller: MonitorController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val rssi by controller.rssi.collectAsState()
    val running = controller.session != null
    val supportsLongRange = controller.link.supportsLongRange

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("< Back") }
        Text("Link test", style = MaterialTheme.typography.titleMedium)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = controller.longRange && supportsLongRange,
            onCheckedChange = { controller.longRange = it },
            enabled = !running && supportsLongRange,
        )
        Text(
            if (supportsLongRange) {
                "Long range (LE Coded) - both phones must be Android with it on. Applies on the next start."
            } else {
                "Long range not available on this phone"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (!running) {
        Text("Start the monitor on the main screen to test the link.", style = MaterialTheme.typography.bodyMedium)
    }
    StatusLine(state, reconnecting = running)
    if (state is LinkState.Connected) {
        Metrics(controller.rttMs, rssi, controller.peerRssi, controller.drops)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::runBulkTest, enabled = !controller.bulkRunning) {
                Text(if (controller.bulkRunning) "Measuring..." else "Throughput test")
            }
            OutlinedButton(onClick = controller::toggleStream) {
                Text(if (controller.streaming) "Stop stream" else "Simulated stream")
            }
        }
    } else if (controller.drops > 0) {
        Text("Drops so far: ${controller.drops}", style = MaterialTheme.typography.bodyMedium)
    }
    controller.audioReport?.let { Text("Sound: $it", style = MaterialTheme.typography.bodySmall) }
    controller.streamReport?.let { Text("Simulated: $it", style = MaterialTheme.typography.bodySmall) }

    HorizontalDivider()
    LogView(controller.logLines, Modifier.weight(1f))
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

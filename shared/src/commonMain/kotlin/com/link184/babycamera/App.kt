package com.link184.babycamera

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.MAX_LISTENERS
import com.link184.babycamera.link.PairingCode
import com.link184.babycamera.link.Peer
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.ncgroup.kscan.BarcodeFormat
import org.ncgroup.kscan.BarcodeResult
import org.ncgroup.kscan.ScannerView

private enum class Screen { Monitor, LinkTest, ScanBabyUnit }

@Composable
fun App(controller: MonitorController, cameraPermission: CameraPermission) {
    var screen by remember { mutableStateOf(Screen.Monitor) }
    var chosenRole by remember { mutableStateOf(MonitorRole.Parent) }
    // Follows the phone's dark mode setting, and switches live when it changes.
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val back = { screen = Screen.Monitor }
                when (screen) {
                    Screen.Monitor -> MonitorScreen(
                        controller,
                        chosenRole = chosenRole,
                        onChooseRole = { chosenRole = it },
                        onScan = { screen = Screen.ScanBabyUnit },
                        onLinkTest = { screen = Screen.LinkTest },
                    )
                    Screen.LinkTest -> LinkTestScreen(controller, onBack = back)
                    Screen.ScanBabyUnit -> ScanScreen(cameraPermission, onScanned = controller::pairWith, onDone = back)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MonitorScreen(
    controller: MonitorController,
    chosenRole: MonitorRole,
    onChooseRole: (MonitorRole) -> Unit,
    onScan: () -> Unit,
    onLinkTest: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var showQrCode by remember { mutableStateOf(false) }
    val running = controller.session
    val role = running?.role ?: chosenRole

    Text("BabyCamera", style = MaterialTheme.typography.headlineSmall)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoleCard(
            title = "Baby",
            description = "Stays next to the baby and listens",
            selected = role == MonitorRole.Baby,
            enabled = running == null,
            onClick = { onChooseRole(MonitorRole.Baby) },
            modifier = Modifier.weight(1f),
        )
        RoleCard(
            title = "Parent",
            description = "Plays the sound and alerts you",
            selected = role == MonitorRole.Parent,
            enabled = running == null,
            onClick = { onChooseRole(MonitorRole.Parent) },
            modifier = Modifier.weight(1f),
        )
    }

    when (role) {
        MonitorRole.Baby -> OutlinedButton(onClick = { showQrCode = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add a parent unit")
        }
        MonitorRole.Parent -> PairedUnitLine(controller.pairedUnitId, enabled = running == null, onScan = onScan)
    }
    if (showQrCode) PairingQrDialog(controller.pairingCode, controller.ownUnitId, onDismiss = { showQrCode = false })

    if (running == null) {
        Button(
            onClick = { controller.start(chosenRole) },
            enabled = chosenRole == MonitorRole.Baby || controller.pairedUnitId != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (chosenRole == MonitorRole.Baby) "Start listening to the baby" else "Start receiving")
        }
        controller.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } else {
        OutlinedButton(onClick = controller::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        StatusLine(state, reconnecting = true)
        if (role == MonitorRole.Baby) {
            val listening = (state as? LinkState.Connected)?.peers?.size ?: 0
            Text(
                if (listening < MAX_LISTENERS) {
                    "Parent units listening: $listening of $MAX_LISTENERS. Tap \"Add a parent unit\" to add another."
                } else {
                    "Parent units listening: $listening of $MAX_LISTENERS (full)."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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

/** On the parent unit: which baby unit it listens to, and how to (re)scan it. */
@Composable
private fun PairedUnitLine(unitId: String?, enabled: Boolean, onScan: () -> Unit) {
    if (unitId == null) {
        Text(
            "First, scan the QR code on the baby unit: on that phone choose Baby and tap \"Add a parent unit\".",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onScan, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Scan the baby unit's QR code")
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Baby unit ${PairingCode.shortName(unitId)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onScan, enabled = enabled) { Text("Scan another") }
        }
    }
}

/** On the baby unit: the code each parent unit scans once. */
@Composable
private fun PairingQrDialog(code: String, unitId: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Add a parent unit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Dark on white whatever the theme, so every camera can read it.
                Box(Modifier.background(Color.White, MaterialTheme.shapes.medium).padding(16.dp)) {
                    Image(rememberQrCodePainter(code) {}, contentDescription = "Pairing QR code", modifier = Modifier.size(220.dp))
                }
                Text("Baby unit ${PairingCode.shortName(unitId)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "On the parent phone, choose Parent and tap \"Scan the baby unit's QR code\". The first time " +
                        "they connect, both phones ask to pair over Bluetooth: check that the 6-digit numbers " +
                        "match, then accept on both.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/** Full-screen camera that reads the baby unit's QR code, then returns. */
@Composable
private fun ColumnScope.ScanScreen(cameraPermission: CameraPermission, onScanned: (String) -> Boolean, onDone: () -> Unit) {
    var granted by remember { mutableStateOf<Boolean?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { cameraPermission.request { granted = it } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDone) { Text("< Back") }
        Text("Scan the baby unit's QR code", style = MaterialTheme.typography.titleMedium)
    }
    when (granted) {
        null -> Text("Asking for the camera...", style = MaterialTheme.typography.bodyMedium)
        false -> Text(
            "BabyCamera needs the camera to read the QR code. Allow it in the phone's settings, then try again.",
            color = MaterialTheme.colorScheme.error,
        )
        true -> {
            Text(
                "On the baby unit, tap \"Add a parent unit\" and point this camera at the code.",
                style = MaterialTheme.typography.bodyMedium,
            )
            problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Button(onClick = { problem = null; attempt++ }) { Text("Try again") }
            }
            // The scanner stops at its first result, so a new attempt needs a new scanner.
            if (problem == null) {
                key(attempt) {
                    ScannerView(
                        codeTypes = listOf(BarcodeFormat.FORMAT_QR_CODE),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) { result ->
                        when (result) {
                            is BarcodeResult.OnSuccess ->
                                if (onScanned(result.barcode.data)) onDone() else problem = "That isn't a BabyCamera code."
                            is BarcodeResult.OnFailed -> problem = "Scanning failed: ${result.exception.message}"
                        }
                    }
                }
            }
        }
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
    val connected = state as? LinkState.Connected
    if (connected != null) {
        Metrics(connected.peers, controller.rttMs, rssi, controller.peerRssi, controller.drops)
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
        is LinkState.Connected -> "Connected to ${state.peers.joinToString { it.name }}"
        is LinkState.Failed -> if (reconnecting) "Lost: ${state.reason} - retrying" else "Failed: ${state.reason}"
    }
    val color = when (state) {
        is LinkState.Failed -> MaterialTheme.colorScheme.error
        is LinkState.Connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
}

/** One line per connected peer. [rssi] is this phone's own measurement, which only a joiner has. */
@Composable
private fun Metrics(peers: List<Peer>, rttMs: Map<Int, Double>, rssi: Int?, peerRssi: Map<Int, Int>, drops: Int) {
    for (peer in peers) {
        val parts = buildList {
            rttMs[peer.id]?.let { add("round trip ${(it * 10).toLong() / 10.0} ms") }
            (rssi ?: peerRssi[peer.id])?.let { add("signal $it dBm") }
        }
        Text(
            "${peer.name}: ${parts.joinToString("  -  ").ifEmpty { "measuring..." }}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text("Drops so far: $drops", style = MaterialTheme.typography.bodyMedium)
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

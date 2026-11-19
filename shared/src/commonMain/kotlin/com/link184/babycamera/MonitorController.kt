package com.link184.babycamera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.link184.babycamera.audio.AUDIO_FRAME_MS
import com.link184.babycamera.audio.AUDIO_SAMPLE_RATE
import com.link184.babycamera.audio.AdpcmState
import com.link184.babycamera.audio.AudioPacket
import com.link184.babycamera.audio.FrameAssembler
import com.link184.babycamera.audio.LoudnessDetector
import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.FrameDecoder
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.PairingCode
import com.link184.babycamera.link.Peer
import com.link184.babycamera.link.PeerEvent
import com.link184.babycamera.link.PeerLink
import com.link184.babycamera.link.Role
import com.link184.babycamera.link.getInt
import com.link184.babycamera.link.getLong
import com.link184.babycamera.link.putInt
import com.link184.babycamera.link.putLong
import com.link184.babycamera.security.Pairings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * What the user asked for; kept while the link drops so it can reconnect by itself. [unitId] is
 * the baby unit's ID: this phone's own on the baby unit, the scanned one on a parent unit.
 */
data class Session(val role: MonitorRole, val longRange: Boolean, val unitId: String)

/**
 * Runs the baby monitor. The baby unit hosts the Bluetooth link and streams its microphone to
 * every parent unit that joins (up to [com.link184.babycamera.link.MAX_LISTENERS]); each parent
 * unit plays the sound and alerts when it stays above [threshold]. Both sides ping each other once
 * a second, and parent units reconnect by themselves after drops. The link test screen adds a
 * throughput test and a simulated audio stream on top of the same link.
 *
 * Meant to live as long as the app process, not a screen, so it keeps going in the background.
 * Must be used from the main thread. Everything it touches comes in through the constructor, so
 * tests can run it with fakes and virtual time.
 */
class MonitorController(
    val link: PeerLink,
    private val microphone: AudioInput,
    private val speaker: AudioOutput,
    private val system: MonitorSystem,
    /** Which baby unit and parent units belong together; the link's keys come from here too. */
    val pairings: Pairings,
    private val scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
) {

    val state: StateFlow<LinkState> = link.state
    val rssi: StateFlow<Int?> = link.rssi
    val logLines = mutableStateListOf<String>()

    var session by mutableStateOf<Session?>(null)
        private set

    /** Used by the next [start]; set on the link test screen. */
    var longRange by mutableStateOf(false)

    private val rttByPeer = mutableStateMapOf<Int, Double>()
    private val rssiByPeer = mutableStateMapOf<Int, Int>()

    /** Round trip to each connected peer, by [Peer.id]. */
    val rttMs: Map<Int, Double> = rttByPeer

    /** Signal strength each peer measured, by [Peer.id]. */
    val peerRssi: Map<Int, Int> = rssiByPeer

    /** Connections lost while running: to the baby unit, or of parent units on the baby unit. */
    var drops by mutableIntStateOf(0)
        private set

    /** Sound level from 0 to 1: this microphone's on the baby unit, the received one on the parent unit. */
    var level by mutableStateOf<Float?>(null)
        private set

    /** The parent unit alerts when [level] stays above this. */
    var threshold by mutableFloatStateOf(system.loadThreshold() ?: DEFAULT_THRESHOLD)
        private set
    var crying by mutableStateOf(false)
        private set
    var startError by mutableStateOf<String?>(null)
        private set

    /** Reception stats of the real sound, on the parent unit. */
    var audioReport by mutableStateOf<String?>(null)
        private set

    var bulkRunning by mutableStateOf(false)
        private set
    var streaming by mutableStateOf(false)
        private set
    var streamReport by mutableStateOf<String?>(null)
        private set

    private val clock = timeSource.markNow()

    /** One per open connection, by [Peer.id]. */
    private val peers = LinkedHashMap<Int, PeerSession>()

    /** Encoded sound from the microphone thread; drops the oldest when the link can't keep up. */
    private val captured = Channel<ByteArray>(CAPTURE_QUEUE_FRAMES, BufferOverflow.DROP_OLDEST)
    private var senderJob: Job? = null
    private val loudness = LoudnessDetector(LOUD_WINDOW_FRAMES, LOUD_FRAMES_NEEDED)
    private var lastAlertNanos: Long? = null

    private var streamJob: Job? = null

    /** Peers whose throughput result hasn't come back yet. */
    private val bulkWaiting = mutableSetOf<Int>()

    init {
        scope.launch { link.log.collect(::log) }
        scope.launch { link.events.collect(::onEvent) }
        scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (link.state.value !is LinkState.Connected) continue
                broadcast(Frame(Frame.PING, ByteArray(8).also { it.putLong(0, nowNanos()) }))
                link.rssi.value?.let { broadcast(Frame(Frame.RSSI, ByteArray(4).also { b -> b.putInt(0, it) })) }
            }
        }
        scope.launch {
            link.state.collectLatest { state ->
                session?.let { system.updateStatus(it.role, statusText(it.role, state)) }
                if (state is LinkState.Connected) return@collectLatest
                if (session?.role == MonitorRole.Parent) level = null
                stopStream()
                val wanted = session
                if (state is LinkState.Failed && wanted != null) {
                    delay(RECONNECT_DELAY_MS)
                    if (session == wanted) {
                        log("Reconnecting...")
                        link.start(wanted.role.linkRole, wanted.longRange, wanted.unitId)
                    }
                }
            }
        }
    }

    fun start(role: MonitorRole) {
        if (session != null) return
        val unitId = when (role) {
            MonitorRole.Baby -> pairings.ownUnitId
            MonitorRole.Parent -> pairings.babyUnit.value?.unitId ?: run {
                startError = "Scan the baby unit's QR code first"
                return
            }
        }
        logLines.clear()
        drops = 0
        audioReport = null
        streamReport = null
        startError = null
        dismissAlert()
        loudness.reset()
        lastAlertNanos = null

        val audioError = when (role) {
            MonitorRole.Baby -> startMicrophone()
            MonitorRole.Parent -> speaker.start(AUDIO_SAMPLE_RATE)
        }
        if (audioError != null) {
            startError = audioError
            log("FAILED: $audioError")
            return
        }
        val wanted = Session(role, longRange && link.supportsLongRange, unitId)
        session = wanted
        system.keepRunning(role)?.let { log("WARNING: may stop in the background: $it") }
        log("Started as the ${if (role == MonitorRole.Baby) "baby" else "parent"} unit")
        link.start(role.linkRole, wanted.longRange, unitId)
    }

    /** Parent unit: remembers the baby unit and key in a scanned QR code. False if it isn't a BabyCamera code. */
    fun pairWith(scanned: String): Boolean {
        if (!pairings.pairWith(scanned)) return false
        startError = null
        pairings.babyUnit.value?.let { log("Paired with baby unit ${PairingCode.shortName(it.unitId)}") }
        return true
    }

    /** Baby unit: a QR code with a new key, for one more parent unit. */
    fun newParentCode(): String = pairings.newParentCode()

    /** Baby unit: the parent unit's key stops working, and it is disconnected if connected. */
    fun removeParent(id: String) {
        pairings.removeParent(id)
        log("Parent unit $id removed")
    }

    fun stop() {
        session = null
        link.stop()
        forgetPeers()
        senderJob?.cancel()
        senderJob = null
        microphone.stop()
        speaker.stop()
        level = null
        dismissAlert()
        system.stopRunning()
    }

    fun updateThreshold(value: Float) {
        threshold = value
        system.saveThreshold(value)
    }

    fun dismissAlert() {
        crying = false
        system.clearAlert()
    }

    /** Sends as fast as possible to every connected peer at once; each one reports what it got. */
    fun runBulkTest() {
        val targets = connectedPeers()
        if (bulkRunning || targets.isEmpty()) return
        bulkRunning = true
        bulkWaiting.clear()
        targets.forEach { bulkWaiting += it.id }
        log("Throughput test: sending to ${targets.size} phone(s) as fast as possible for ${BULK_SECONDS}s...")
        val chunk = Frame(Frame.BULK, ByteArray(BULK_CHUNK_BYTES)).encode()
        for (peer in targets) {
            scope.launch {
                val started = nowNanos()
                while (peer in connectedPeers() && nowNanos() - started < BULK_SECONDS * 1_000_000_000L) {
                    link.send(peer, chunk)
                    yield()
                }
                link.send(peer, Frame(Frame.BULK_END, ByteArray(0)).encode())
            }
        }
    }

    /** Sends a 24 kbps packet stream (60 bytes every 20 ms) to every peer, on top of any real sound. */
    fun toggleStream() {
        if (streaming) return stopStream()
        if (link.state.value !is LinkState.Connected) return
        streaming = true
        log("Simulated audio stream started (${STREAM_PACKET_BYTES * 8 * 1000 / STREAM_INTERVAL_MS / 1000} kbps)")
        streamJob = scope.launch {
            var seq = 0
            var next = nowNanos()
            while (true) {
                val payload = ByteArray(STREAM_PACKET_BYTES).also { it.putInt(0, seq++) }
                broadcast(Frame(Frame.STREAM, payload))
                next += STREAM_INTERVAL_MS * 1_000_000L
                val wait = (next - nowNanos()) / 1_000_000
                if (wait > 0) delay(wait) else yield()
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        if (streaming) log("Simulated audio stream stopped")
        streaming = false
    }

    private fun startMicrophone(): String? {
        while (captured.tryReceive().isSuccess) { } // leftovers from the previous session
        // These two are only touched on the microphone thread.
        var seq = 0
        val adpcm = AdpcmState()
        val assembler = FrameAssembler { pcm -> captured.trySend(AudioPacket.encode(seq++, pcm, adpcm)) }
        microphone.start(assembler::add)?.let { return it }
        senderJob = scope.launch {
            for (payload in captured) {
                level = AudioPacket.levelOf(payload)
                // Every packet decodes on its own, so a listener that misses some just hears a gap.
                broadcast(Frame(Frame.AUDIO, payload))
            }
        }
        return null
    }

    /** To every connected peer; a congested one misses it. */
    private fun broadcast(frame: Frame) {
        val peers = connectedPeers()
        if (peers.isEmpty()) return
        val bytes = frame.encode()
        for (peer in peers) link.offer(peer, bytes)
    }

    private fun connectedPeers(): List<Peer> = (link.state.value as? LinkState.Connected)?.peers.orEmpty()

    private fun forgetPeers() {
        peers.clear()
        rttByPeer.clear()
        rssiByPeer.clear()
        bulkWaiting.clear()
        bulkRunning = false
    }

    private fun onEvent(event: PeerEvent) {
        val peer = event.peer
        when (event) {
            is PeerEvent.Joined -> {
                peers[peer.id] = PeerSession(peer)
                if (session?.role == MonitorRole.Baby) log("Parent unit ${peer.name} joined (${peers.size} listening)")
            }

            is PeerEvent.Data -> {
                val from = peers[peer.id] ?: return
                val frames = try {
                    from.decoder.feed(event.bytes)
                } catch (e: IllegalArgumentException) {
                    log("${e.message} from ${peer.name} - dropping it")
                    link.disconnect(peer)
                    return
                }
                frames.forEach { handle(from, it) }
            }

            is PeerEvent.Left -> {
                peers.remove(peer.id) ?: return
                rttByPeer.remove(peer.id)
                rssiByPeer.remove(peer.id)
                if (bulkWaiting.remove(peer.id)) bulkRunning = bulkWaiting.isNotEmpty()
                if (session != null) {
                    drops++
                    log("LINK LOST to ${peer.name} (drop #$drops, ${peers.size} still connected)")
                }
            }
        }
    }

    /** Replies without holding up frames from the other peers. */
    private fun reply(peer: Peer, frame: Frame) {
        scope.launch { link.send(peer, frame.encode()) }
    }

    private fun handle(from: PeerSession, frame: Frame) {
        val peer = from.peer
        when (frame.type) {
            Frame.PING -> reply(peer, Frame(Frame.PONG, frame.payload))

            Frame.PONG -> if (frame.payload.size == 8) {
                rttByPeer[peer.id] = (nowNanos() - frame.payload.getLong(0)) / 1_000_000.0
            }

            Frame.RSSI -> if (frame.payload.size == 4) rssiByPeer[peer.id] = frame.payload.getInt(0)

            Frame.AUDIO -> onAudio(from, frame.payload)

            Frame.BULK -> {
                if (from.bulkReceivedBytes == 0L) from.bulkStartedNanos = nowNanos()
                from.bulkReceivedBytes += frame.payload.size
            }

            Frame.BULK_END -> {
                val millis = ((nowNanos() - from.bulkStartedNanos) / 1_000_000).coerceAtLeast(1)
                val kbps = from.bulkReceivedBytes * 8 / millis
                val result = "$kbps kbps (${from.bulkReceivedBytes} bytes in $millis ms)"
                from.bulkReceivedBytes = 0
                log("Throughput received from ${peer.name}: $result")
                reply(peer, Frame(Frame.BULK_RESULT, result.encodeToByteArray()))
            }

            Frame.BULK_RESULT -> {
                bulkWaiting.remove(peer.id)
                bulkRunning = bulkWaiting.isNotEmpty()
                log("Throughput measured by ${peer.name}: ${frame.payload.decodeToString()}")
            }

            Frame.STREAM -> if (frame.payload.size >= 4) {
                val report = from.streamStats.onPacket(frame.payload.getInt(0), nowNanos()) ?: return
                streamReport = report
                log("Simulated stream from ${peer.name}: $report")
                reply(peer, Frame(Frame.STREAM_REPORT, report.encodeToByteArray()))
            }

            Frame.STREAM_REPORT -> {
                streamReport = "${peer.name} receives: ${frame.payload.decodeToString()}"
                log("Simulated stream at ${peer.name}: ${frame.payload.decodeToString()}")
            }
        }
    }

    private fun onAudio(from: PeerSession, payload: ByteArray) {
        if (session?.role != MonitorRole.Parent) return
        val packet = AudioPacket.decode(payload) ?: return
        val now = nowNanos()
        level = packet.level
        speaker.write(packet.pcm)
        from.audioStats.onPacket(packet.seq, now)?.let {
            audioReport = it
            log("Sound: $it")
        }
        if (loudness.onFrame(packet.level >= threshold)) {
            val last = lastAlertNanos
            if (last == null || now - last >= ALERT_REPEAT_MS * 1_000_000L) {
                lastAlertNanos = now
                crying = true
                log("ALERT: sound above ${percent(threshold)} for over a second")
                system.cryAlert()
            }
        }
    }

    private fun statusText(role: MonitorRole, state: LinkState): String = when (state) {
        LinkState.Idle -> "Stopped"
        is LinkState.Working -> state.step
        is LinkState.Connected -> when {
            role == MonitorRole.Parent -> "Connected - listening to the baby"
            state.peers.size == 1 -> "Connected - 1 parent unit is listening"
            else -> "Connected - ${state.peers.size} parent units are listening"
        }
        is LinkState.Failed -> "Connection lost - retrying"
    }

    private fun nowNanos(): Long = clock.elapsedNow().inWholeNanoseconds

    private fun log(message: String) {
        val seconds = clock.elapsedNow().inWholeMilliseconds / 1000.0
        logLines += "${(seconds * 10).toLong() / 10.0}s  $message"
        if (logLines.size > MAX_LOG_LINES) logLines.removeRange(0, logLines.size - MAX_LOG_LINES)
    }

    /** What this side tracks about one open connection. */
    private class PeerSession(val peer: Peer) {
        val decoder = FrameDecoder()
        val audioStats = StreamStats(AUDIO_FRAME_MS)
        val streamStats = StreamStats(STREAM_INTERVAL_MS)
        var bulkReceivedBytes = 0L
        var bulkStartedNanos = 0L
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.6f
        const val RECONNECT_DELAY_MS = 2_000L
        const val PING_INTERVAL_MS = 1_000L
        const val CAPTURE_QUEUE_FRAMES = 25 // 1 s of sound

        /** Alert when at least 1 s of the last 3 s was loud, so a door bang doesn't count. */
        val LOUD_WINDOW_FRAMES = (3_000 / AUDIO_FRAME_MS).toInt()
        val LOUD_FRAMES_NEEDED = (1_000 / AUDIO_FRAME_MS).toInt()

        /** While the crying goes on, alert again this often. */
        const val ALERT_REPEAT_MS = 30_000L

        const val BULK_SECONDS = 10L
        const val BULK_CHUNK_BYTES = 4 * 1024
        const val STREAM_PACKET_BYTES = 60
        const val STREAM_INTERVAL_MS = 20L
        const val MAX_LOG_LINES = 500
    }
}

private val MonitorRole.linkRole: Role
    get() = if (this == MonitorRole.Baby) Role.Host else Role.Join

fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

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
 * Must be used from the main thread.
 */
class MonitorController(
    val link: PeerLink,
    private val microphone: AudioInput,
    private val speaker: AudioOutput,
    private val system: MonitorSystem,
    private val scope: CoroutineScope,
) {

    val state: StateFlow<LinkState> = link.state
    val rssi: StateFlow<Int?> = link.rssi
    val logLines = mutableStateListOf<String>()

    var session by mutableStateOf<Session?>(null)
        private set

    /** Used by the next [start]; set on the link test screen. */
    var longRange by mutableStateOf(false)

    /** This phone's ID as a baby unit, made the first time it's needed and kept from then on. */
    val ownUnitId: String by lazy {
        system.loadText(KEY_OWN_UNIT) ?: PairingCode.newUnitId().also { system.saveText(KEY_OWN_UNIT, it) }
    }

    /** What the baby unit shows as a QR code for parent units to scan. */
    val pairingCode: String get() = PairingCode.format(ownUnitId)

    /** The baby unit this phone listens to as a parent unit, from its QR code; null until scanned. */
    var pairedUnitId by mutableStateOf(system.loadText(KEY_PAIRED_UNIT))
        private set

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

    private val clock = TimeSource.Monotonic.markNow()

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
                link.broadcast(Frame(Frame.PING, ByteArray(8).also { it.putLong(0, nowNanos()) }).encode())
                link.rssi.value?.let { link.broadcast(Frame(Frame.RSSI, ByteArray(4).also { b -> b.putInt(0, it) }).encode()) }
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
            MonitorRole.Baby -> ownUnitId
            MonitorRole.Parent -> pairedUnitId ?: run {
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

    /** Remembers the baby unit in a scanned QR code. Returns false if it isn't a BabyCamera code. */
    fun pairWith(scanned: String): Boolean {
        val unitId = PairingCode.parse(scanned) ?: return false
        pairedUnitId = unitId
        system.saveText(KEY_PAIRED_UNIT, unitId)
        startError = null
        log("Paired with baby unit ${PairingCode.shortName(unitId)}")
        return true
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
                link.broadcast(Frame(Frame.STREAM, payload).encode())
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
                if (link.state.value is LinkState.Connected) link.broadcast(Frame(Frame.AUDIO, payload).encode())
            }
        }
        return null
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

    /**
     * Receiver-side stats for a packet stream sent every [intervalMs]. A gap longer than
     * [STALL_MS] between packets is what a listener would hear as a dropout.
     */
    private class StreamStats(private val intervalMs: Long) {
        private var windowStart = 0L
        private var lastArrival = 0L
        private var lastSeq = -1
        private var packets = 0
        private var maxGapMs = 0L
        private var stalls = 0
        private var missing = 0

        fun reset() {
            windowStart = 0
            lastArrival = 0
            lastSeq = -1
            stalls = 0
            missing = 0
        }

        /** Returns a report line every [REPORT_EVERY_MS], otherwise null. */
        fun onPacket(seq: Int, now: Long): String? {
            if (lastSeq >= 0 && seq < lastSeq) reset() // the sender restarted its stream
            if (windowStart == 0L) {
                windowStart = now
                packets = 0
                maxGapMs = 0
            } else {
                val gapMs = (now - lastArrival) / 1_000_000
                if (gapMs > maxGapMs) maxGapMs = gapMs
                if (gapMs > STALL_MS) stalls++
            }
            if (lastSeq >= 0 && seq > lastSeq + 1) missing += seq - lastSeq - 1
            lastSeq = seq
            lastArrival = now
            packets++

            val windowMs = (now - windowStart) / 1_000_000
            if (windowMs < REPORT_EVERY_MS) return null
            val expected = windowMs / intervalMs
            val report = "$packets/$expected packets in ${windowMs / 1000}s, longest gap $maxGapMs ms, " +
                "dropouts (>$STALL_MS ms) $stalls, missing $missing"
            windowStart = now
            packets = 0
            maxGapMs = 0
            return report
        }
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.6f
        const val KEY_OWN_UNIT = "own_unit_id"
        const val KEY_PAIRED_UNIT = "paired_unit_id"
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
        const val STALL_MS = 250L
        const val REPORT_EVERY_MS = 5_000L
        const val MAX_LOG_LINES = 500
    }
}

private val MonitorRole.linkRole: Role
    get() = if (this == MonitorRole.Baby) Role.Host else Role.Join

fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

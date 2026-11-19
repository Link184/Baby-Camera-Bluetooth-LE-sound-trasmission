package com.link184.babycamera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** What the user asked for; kept while the link drops so it can reconnect by itself. */
data class Session(val role: MonitorRole, val longRange: Boolean)

/**
 * Runs the baby monitor. The baby unit hosts the Bluetooth link and streams its microphone; the
 * parent unit joins, plays the sound, and alerts when the sound stays above [threshold]. Both sides
 * ping each other once a second and reconnect by themselves after drops. The link test screen adds
 * a throughput test and a simulated audio stream on top of the same link.
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

    var rttMs by mutableStateOf<Double?>(null)
        private set
    var peerRssi by mutableStateOf<Int?>(null)
        private set
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
    private var decoder = FrameDecoder()

    /** Encoded sound from the microphone thread; drops the oldest when the link can't keep up. */
    private val captured = Channel<ByteArray>(CAPTURE_QUEUE_FRAMES, BufferOverflow.DROP_OLDEST)
    private var senderJob: Job? = null
    private val audioStats = StreamStats(AUDIO_FRAME_MS)
    private val loudness = LoudnessDetector(LOUD_WINDOW_FRAMES, LOUD_FRAMES_NEEDED)
    private var lastAlertNanos: Long? = null

    private var streamJob: Job? = null
    private val streamStats = StreamStats(STREAM_INTERVAL_MS)
    private var bulkReceivedBytes = 0L
    private var bulkStartedNanos = 0L

    init {
        scope.launch { link.log.collect(::log) }
        scope.launch {
            link.incoming.collect { chunk ->
                val frames = try {
                    decoder.feed(chunk)
                } catch (e: IllegalArgumentException) {
                    log("${e.message} - dropping the link")
                    link.stop()
                    emptyList()
                }
                frames.forEach { handle(it) }
            }
        }
        scope.launch {
            var wasConnected = false
            link.state.collectLatest { state ->
                session?.let { system.updateStatus(it.role, statusText(it.role, state)) }
                if (state is LinkState.Connected) {
                    wasConnected = true
                    onConnected()
                    return@collectLatest
                }
                if (wasConnected && session != null) {
                    drops++
                    log("LINK LOST (drop #$drops)")
                }
                wasConnected = false
                rttMs = null
                peerRssi = null
                if (session?.role == MonitorRole.Parent) level = null
                stopStream()
                bulkRunning = false
                val wanted = session
                if (state is LinkState.Failed && wanted != null) {
                    delay(RECONNECT_DELAY_MS)
                    if (session == wanted) {
                        log("Reconnecting...")
                        link.start(wanted.role.linkRole, wanted.longRange)
                    }
                }
            }
        }
    }

    fun start(role: MonitorRole) {
        if (session != null) return
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
        val wanted = Session(role, longRange && link.supportsLongRange)
        session = wanted
        system.keepRunning(role)?.let { log("WARNING: may stop in the background: $it") }
        log("Started as the ${if (role == MonitorRole.Baby) "baby" else "parent"} unit")
        link.start(role.linkRole, wanted.longRange)
    }

    fun stop() {
        session = null
        link.stop()
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

    fun runBulkTest() {
        if (bulkRunning || link.state.value !is LinkState.Connected) return
        bulkRunning = true
        scope.launch {
            log("Throughput test: sending as fast as possible for ${BULK_SECONDS}s...")
            val chunk = Frame(Frame.BULK, ByteArray(BULK_CHUNK_BYTES)).encode()
            val started = nowNanos()
            while (link.state.value is LinkState.Connected && nowNanos() - started < BULK_SECONDS * 1_000_000_000L) {
                link.send(chunk)
            }
            link.send(Frame(Frame.BULK_END, ByteArray(0)).encode())
        }
    }

    /** Sends a 24 kbps packet stream (60 bytes every 20 ms), on top of any real sound. */
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
                link.send(Frame(Frame.STREAM, payload).encode())
                next += STREAM_INTERVAL_MS * 1_000_000L
                val wait = (next - nowNanos()) / 1_000_000
                if (wait > 0) delay(wait)
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
                if (link.state.value is LinkState.Connected) link.send(Frame(Frame.AUDIO, payload).encode())
            }
        }
        return null
    }

    private suspend fun onConnected() {
        decoder = FrameDecoder()
        bulkReceivedBytes = 0
        audioStats.reset()
        streamStats.reset()
        while (true) {
            link.send(Frame(Frame.PING, ByteArray(8).also { it.putLong(0, nowNanos()) }).encode())
            link.rssi.value?.let { link.send(Frame(Frame.RSSI, ByteArray(4).also { b -> b.putInt(0, it) }).encode()) }
            delay(1_000)
        }
    }

    private suspend fun handle(frame: Frame) {
        when (frame.type) {
            Frame.PING -> link.send(Frame(Frame.PONG, frame.payload).encode())

            Frame.PONG -> if (frame.payload.size == 8) {
                rttMs = (nowNanos() - frame.payload.getLong(0)) / 1_000_000.0
            }

            Frame.RSSI -> if (frame.payload.size == 4) peerRssi = frame.payload.getInt(0)

            Frame.AUDIO -> onAudio(frame.payload)

            Frame.BULK -> {
                if (bulkReceivedBytes == 0L) bulkStartedNanos = nowNanos()
                bulkReceivedBytes += frame.payload.size
            }

            Frame.BULK_END -> {
                val millis = ((nowNanos() - bulkStartedNanos) / 1_000_000).coerceAtLeast(1)
                val kbps = bulkReceivedBytes * 8 / millis
                val result = "$kbps kbps ($bulkReceivedBytes bytes in $millis ms)"
                bulkReceivedBytes = 0
                log("Throughput received: $result")
                link.send(Frame(Frame.BULK_RESULT, result.encodeToByteArray()).encode())
            }

            Frame.BULK_RESULT -> {
                bulkRunning = false
                log("Throughput measured by peer: ${frame.payload.decodeToString()}")
            }

            Frame.STREAM -> if (frame.payload.size >= 4) {
                val report = streamStats.onPacket(frame.payload.getInt(0), nowNanos()) ?: return
                streamReport = report
                log("Simulated stream: $report")
                link.send(Frame(Frame.STREAM_REPORT, report.encodeToByteArray()).encode())
            }

            Frame.STREAM_REPORT -> {
                streamReport = "peer receives: ${frame.payload.decodeToString()}"
                log("Simulated stream at peer: ${frame.payload.decodeToString()}")
            }
        }
    }

    private fun onAudio(payload: ByteArray) {
        if (session?.role != MonitorRole.Parent) return
        val packet = AudioPacket.decode(payload) ?: return
        val now = nowNanos()
        level = packet.level
        speaker.write(packet.pcm)
        audioStats.onPacket(packet.seq, now)?.let {
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
        is LinkState.Connected ->
            if (role == MonitorRole.Baby) "Connected - the parent unit is listening" else "Connected - listening to the baby"
        is LinkState.Failed -> "Connection lost - retrying"
    }

    private fun nowNanos(): Long = clock.elapsedNow().inWholeNanoseconds

    private fun log(message: String) {
        val seconds = clock.elapsedNow().inWholeMilliseconds / 1000.0
        logLines += "${(seconds * 10).toLong() / 10.0}s  $message"
        if (logLines.size > MAX_LOG_LINES) logLines.removeRange(0, logLines.size - MAX_LOG_LINES)
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
        const val RECONNECT_DELAY_MS = 2_000L
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

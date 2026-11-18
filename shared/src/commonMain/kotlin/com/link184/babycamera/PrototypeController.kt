package com.link184.babycamera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.FrameDecoder
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.PeerLink
import com.link184.babycamera.link.Role
import com.link184.babycamera.link.getInt
import com.link184.babycamera.link.getLong
import com.link184.babycamera.link.putInt
import com.link184.babycamera.link.putLong
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the user asked for; kept while the link drops so it can reconnect by itself. */
data class Session(val role: Role, val longRange: Boolean)

/**
 * Drives the link test: pings the peer once a second, exchanges signal strength, reconnects after
 * drops, and runs a throughput test and an audio-like stream test. Must be used from the main
 * thread.
 */
class PrototypeController(private val link: PeerLink, private val scope: CoroutineScope) {

    val state: StateFlow<LinkState> = link.state
    val rssi: StateFlow<Int?> = link.rssi
    val logLines = mutableStateListOf<String>()

    var session by mutableStateOf<Session?>(null)
        private set
    var rttMs by mutableStateOf<Double?>(null)
        private set
    var peerRssi by mutableStateOf<Int?>(null)
        private set
    var drops by mutableIntStateOf(0)
        private set
    var bulkRunning by mutableStateOf(false)
        private set
    var streaming by mutableStateOf(false)
        private set
    var streamReport by mutableStateOf<String?>(null)
        private set

    private val clock = TimeSource.Monotonic.markNow()
    private var decoder = FrameDecoder()
    private var streamJob: Job? = null

    private var bulkReceivedBytes = 0L
    private var bulkStartedNanos = 0L

    private val streamStats = StreamStats()

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
                if (state is LinkState.Connected) {
                    wasConnected = true
                    onConnected()
                    return@collectLatest
                }
                if (wasConnected) {
                    wasConnected = false
                    drops++
                    log("LINK LOST (drop #$drops)")
                }
                rttMs = null
                peerRssi = null
                stopStream()
                bulkRunning = false
                val wanted = session
                if (state is LinkState.Failed && wanted != null) {
                    delay(RECONNECT_DELAY_MS)
                    if (session == wanted) {
                        log("Reconnecting...")
                        link.start(wanted.role, wanted.longRange)
                    }
                }
            }
        }
    }

    fun start(role: Role, longRange: Boolean) {
        logLines.clear()
        drops = 0
        streamReport = null
        session = Session(role, longRange).also { link.start(it.role, it.longRange) }
    }

    fun stop() {
        session = null
        link.stop()
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

    /** Sends a 24 kbps packet stream (60 bytes every 20 ms), like compressed voice would be. */
    fun toggleStream() {
        if (streaming) return stopStream()
        if (link.state.value !is LinkState.Connected) return
        streaming = true
        log("Audio-like stream started (${STREAM_PACKET_BYTES * 8 * 1000 / STREAM_INTERVAL_MS / 1000} kbps)")
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
        if (streaming) log("Audio-like stream stopped")
        streaming = false
    }

    private suspend fun onConnected() {
        decoder = FrameDecoder()
        bulkReceivedBytes = 0
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
                log("Stream: $report")
                link.send(Frame(Frame.STREAM_REPORT, report.encodeToByteArray()).encode())
            }

            Frame.STREAM_REPORT -> {
                streamReport = "peer receives: ${frame.payload.decodeToString()}"
                log("Stream at peer: ${frame.payload.decodeToString()}")
            }
        }
    }

    private fun nowNanos(): Long = clock.elapsedNow().inWholeNanoseconds

    private fun log(message: String) {
        val seconds = clock.elapsedNow().inWholeMilliseconds / 1000.0
        logLines += "${(seconds * 10).toLong() / 10.0}s  $message"
        if (logLines.size > MAX_LOG_LINES) logLines.removeRange(0, logLines.size - MAX_LOG_LINES)
    }

    /**
     * Receiver-side stats for the audio-like stream. A gap longer than [STALL_MS] between packets
     * is what a listener would hear as a dropout.
     */
    private class StreamStats {
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
            val expected = windowMs / STREAM_INTERVAL_MS
            val report = "$packets/$expected packets in ${windowMs / 1000}s, longest gap $maxGapMs ms, " +
                "dropouts (>$STALL_MS ms) $stalls, missing $missing"
            windowStart = now
            packets = 0
            maxGapMs = 0
            return report
        }
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2_000L
        const val BULK_SECONDS = 10L
        const val BULK_CHUNK_BYTES = 4 * 1024
        const val STREAM_PACKET_BYTES = 60
        const val STREAM_INTERVAL_MS = 20L
        const val STALL_MS = 250L
        const val REPORT_EVERY_MS = 5_000L
        const val MAX_LOG_LINES = 500
    }
}

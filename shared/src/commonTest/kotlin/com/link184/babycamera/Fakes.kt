package com.link184.babycamera

import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.FrameDecoder
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.Peer
import com.link184.babycamera.link.PeerEvent
import com.link184.babycamera.link.PeerLink
import com.link184.babycamera.link.Role
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A [PeerLink] driven by the test: [join], [receive], [leave] and [fail] play the other phones,
 * and everything the controller sends is recorded, decoded.
 */
class FakePeerLink(override val supportsLongRange: Boolean = false) : PeerLink {
    data class Start(val role: Role, val longRange: Boolean, val unitId: String)

    override val state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val log: Flow<String> = MutableSharedFlow(extraBufferCapacity = 64)
    override val rssi = MutableStateFlow<Int?>(null)
    private val eventChannel = Channel<PeerEvent>(Channel.UNLIMITED)
    override val events: Flow<PeerEvent> = eventChannel.receiveAsFlow()

    val starts = mutableListOf<Start>()
    var stops = 0
    val disconnected = mutableListOf<Peer>()
    val sent = mutableListOf<Pair<Peer, Frame>>()
    val broadcasts = mutableListOf<Frame>()

    /** How long each [send] takes, like a link that is busy sending. */
    var sendDelayMs = 0L

    private val connected = mutableListOf<Peer>()

    fun sentTo(peer: Peer, type: Byte): List<Frame> = sent.filter { it.first == peer && it.second.type == type }.map { it.second }

    fun broadcasts(type: Byte): List<Frame> = broadcasts.filter { it.type == type }

    override fun start(role: Role, longRange: Boolean, unitId: String) {
        starts += Start(role, longRange, unitId)
        state.value = LinkState.Working("Starting")
    }

    override suspend fun send(peer: Peer, bytes: ByteArray) {
        if (sendDelayMs > 0) delay(sendDelayMs)
        if (peer in connected) sent += peer to bytes.toFrame()
    }

    override fun broadcast(bytes: ByteArray) {
        if (connected.isNotEmpty()) broadcasts += bytes.toFrame()
    }

    override fun disconnect(peer: Peer) {
        disconnected += peer
        leave(peer)
    }

    override fun stop() {
        stops++
        connected.toList().forEach(::leave)
        state.value = LinkState.Idle
    }

    fun join(peer: Peer) {
        connected += peer
        eventChannel.trySend(PeerEvent.Joined(peer))
        state.value = LinkState.Connected(connected.toList())
    }

    fun receive(peer: Peer, frame: Frame) = receiveRaw(peer, frame.encode())

    fun receiveRaw(peer: Peer, bytes: ByteArray) {
        eventChannel.trySend(PeerEvent.Data(peer, bytes))
    }

    fun leave(peer: Peer) {
        if (!connected.remove(peer)) return
        eventChannel.trySend(PeerEvent.Left(peer))
        state.value = if (connected.isEmpty()) LinkState.Working("Waiting") else LinkState.Connected(connected.toList())
    }

    fun fail(reason: String) {
        connected.toList().forEach { eventChannel.trySend(PeerEvent.Left(it)) }
        connected.clear()
        state.value = LinkState.Failed(reason)
    }

    private fun ByteArray.toFrame(): Frame = FrameDecoder().feed(this).single()
}

class FakeMicrophone(var startError: String? = null) : AudioInput {
    private var onSamples: ((ShortArray, Int, Int) -> Unit)? = null
    val running get() = onSamples != null

    override fun start(onSamples: (samples: ShortArray, count: Int, sampleRate: Int) -> Unit): String? {
        if (startError == null) this.onSamples = onSamples
        return startError
    }

    override fun stop() {
        onSamples = null
    }

    /** What the microphone hears, at 8 kHz. */
    fun hear(samples: ShortArray) = checkNotNull(onSamples) { "microphone not started" }(samples, samples.size, 8_000)
}

class FakeSpeaker(var startError: String? = null) : AudioOutput {
    var running = false
        private set
    val played = mutableListOf<ShortArray>()

    override fun start(sampleRate: Int): String? {
        running = startError == null
        return startError
    }

    override fun write(samples: ShortArray) {
        played += samples
    }

    override fun stop() {
        running = false
    }
}

/** Settings live in [settings], so a second controller on the same instance sees what the first saved. */
class FakeSystem : MonitorSystem {
    val settings = mutableMapOf<String, String>()
    var threshold: Float? = null
    var keptRunningAs: MonitorRole? = null
    val statuses = mutableListOf<String>()
    var alerts = 0
    var keepRunningProblem: String? = null

    override fun keepRunning(role: MonitorRole): String? {
        keptRunningAs = role
        return keepRunningProblem
    }

    override fun updateStatus(role: MonitorRole, text: String) {
        statuses += text
    }

    override fun stopRunning() {
        keptRunningAs = null
    }

    override fun cryAlert() {
        alerts++
    }

    override fun clearAlert() {}

    override fun loadThreshold(): Float? = threshold

    override fun saveThreshold(value: Float) {
        threshold = value
    }

    override fun loadText(key: String): String? = settings[key]

    override fun saveText(key: String, value: String?) {
        if (value == null) settings.remove(key) else settings[key] = value
    }
}

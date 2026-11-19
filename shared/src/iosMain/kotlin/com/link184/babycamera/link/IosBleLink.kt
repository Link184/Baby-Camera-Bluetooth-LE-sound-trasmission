package com.link184.babycamera.link

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The Bluetooth LE side lives in Swift (BleBridge.swift, using Core Bluetooth) and is passed to
 * `MainViewController`. Every call happens on the main thread. Peers are numbered by the bridge,
 * with a new number for every connection.
 */
interface IosBleBridge {
    /**
     * A host advertises [unitId] (a UUID) and accepts up to [maxPeers] joiners at once; a joiner
     * scans for [unitId].
     */
    fun start(host: Boolean, maxPeers: Int, unitId: String, listener: IosBleListener)

    /** Queues [data] for sending to [peer], in call order. */
    fun send(peer: Int, data: NSData)

    /** Bytes queued for [peer] but not yet handed to the Bluetooth stack. */
    fun pendingBytes(peer: Int): Long

    /** Closes the channel to [peer]; on a joiner this fails the link. */
    fun disconnect(peer: Int)

    /** Closes everything without further callbacks. */
    fun stop()
}

/** Callbacks from Swift, on the main thread. */
interface IosBleListener {
    fun onLog(message: String)

    fun onWorking(step: String)

    fun onPeerConnected(peer: Int, name: String)

    /** One peer is gone; on a host the others carry on. */
    fun onPeerDisconnected(peer: Int)

    /** Everything stopped; the peers still listed are gone too. */
    fun onFailed(reason: String)

    fun onData(peer: Int, data: NSData)

    fun onRssi(rssi: Int)
}

class IosBleLink(private val bridge: IosBleBridge) : PeerLink {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    private val _log = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _rssi = MutableStateFlow<Int?>(null)
    private val eventChannel = Channel<PeerEvent>(Channel.UNLIMITED)

    /** Connected peers by id; main thread only. */
    private val peers = LinkedHashMap<Int, Peer>()
    private var step = "Starting"

    override val state: StateFlow<LinkState> = _state
    override val log: Flow<String> = _log
    override val events: Flow<PeerEvent> = eventChannel.receiveAsFlow()
    override val rssi: StateFlow<Int?> = _rssi

    /** iOS gives apps no control over the Bluetooth radio mode. */
    override val supportsLongRange: Boolean = false

    private val listener = object : IosBleListener {
        override fun onLog(message: String) {
            _log.tryEmit(message)
        }

        override fun onWorking(step: String) {
            this@IosBleLink.step = step
            publishState()
        }

        override fun onPeerConnected(peer: Int, name: String) {
            val added = Peer(peer, name)
            peers[peer] = added
            eventChannel.trySend(PeerEvent.Joined(added))
            publishState()
        }

        override fun onPeerDisconnected(peer: Int) {
            val removed = peers.remove(peer) ?: return
            eventChannel.trySend(PeerEvent.Left(removed))
            publishState()
        }

        override fun onFailed(reason: String) {
            _log.tryEmit("FAILED: $reason")
            dropAllPeers()
            _state.value = LinkState.Failed(reason)
        }

        override fun onData(peer: Int, data: NSData) {
            val from = peers[peer] ?: return
            eventChannel.trySend(PeerEvent.Data(from, data.toByteArray()))
        }

        override fun onRssi(rssi: Int) {
            _rssi.value = rssi
        }
    }

    override fun start(role: Role, longRange: Boolean, unitId: String) {
        dropAllPeers()
        step = "Starting"
        publishState()
        bridge.start(host = role == Role.Host, maxPeers = MAX_LISTENERS, unitId = unitId, listener = listener)
    }

    override suspend fun send(peer: Peer, bytes: ByteArray) {
        while (peers[peer.id] == peer && bridge.pendingBytes(peer.id) > MAX_PENDING_BYTES) delay(5)
        if (peers[peer.id] == peer) bridge.send(peer.id, bytes.toNSData())
    }

    override fun broadcast(bytes: ByteArray) {
        if (peers.isEmpty()) return
        val data = bytes.toNSData()
        for (id in peers.keys) {
            if (bridge.pendingBytes(id) <= MAX_PENDING_BYTES) bridge.send(id, data)
        }
    }

    override fun disconnect(peer: Peer) {
        if (peers[peer.id] == peer) bridge.disconnect(peer.id)
    }

    override fun stop() {
        bridge.stop()
        dropAllPeers()
        _state.value = LinkState.Idle
    }

    private fun publishState() {
        _state.value = if (peers.isEmpty()) LinkState.Working(step) else LinkState.Connected(peers.values.toList())
    }

    private fun dropAllPeers() {
        _rssi.value = null
        peers.values.forEach { eventChannel.trySend(PeerEvent.Left(it)) }
        peers.clear()
    }

    private companion object {
        const val MAX_PENDING_BYTES = 32 * 1024L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

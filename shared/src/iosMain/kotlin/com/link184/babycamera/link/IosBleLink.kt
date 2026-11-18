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
 * `MainViewController`. Every call happens on the main thread.
 */
interface IosBleBridge {
    fun start(host: Boolean, listener: IosBleListener)

    /** Queues [data] for sending, in call order. */
    fun send(data: NSData)

    /** Bytes queued but not yet handed to the Bluetooth stack. */
    fun pendingBytes(): Long

    fun stop()
}

/** Callbacks from Swift, on the main thread. */
interface IosBleListener {
    fun onLog(message: String)

    fun onWorking(step: String)

    fun onConnected(peer: String)

    fun onFailed(reason: String)

    fun onData(data: NSData)

    fun onRssi(rssi: Int)
}

class IosBleLink(private val bridge: IosBleBridge) : PeerLink {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    private val _log = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _rssi = MutableStateFlow<Int?>(null)
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override val state: StateFlow<LinkState> = _state
    override val log: Flow<String> = _log
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()
    override val rssi: StateFlow<Int?> = _rssi

    /** iOS gives apps no control over the Bluetooth radio mode. */
    override val supportsLongRange: Boolean = false

    private val listener = object : IosBleListener {
        override fun onLog(message: String) {
            _log.tryEmit(message)
        }

        override fun onWorking(step: String) {
            _state.value = LinkState.Working(step)
        }

        override fun onConnected(peer: String) {
            _state.value = LinkState.Connected(peer)
        }

        override fun onFailed(reason: String) {
            _log.tryEmit("FAILED: $reason")
            _rssi.value = null
            _state.value = LinkState.Failed(reason)
        }

        override fun onData(data: NSData) {
            incomingChannel.trySend(data.toByteArray())
        }

        override fun onRssi(rssi: Int) {
            _rssi.value = rssi
        }
    }

    override fun start(role: Role, longRange: Boolean) {
        _state.value = LinkState.Working("Starting")
        bridge.start(host = role == Role.Host, listener = listener)
    }

    override suspend fun send(bytes: ByteArray) {
        while (_state.value is LinkState.Connected && bridge.pendingBytes() > MAX_PENDING_BYTES) delay(5)
        if (_state.value is LinkState.Connected) bridge.send(bytes.toNSData())
    }

    override fun stop() {
        bridge.stop()
        _rssi.value = null
        _state.value = LinkState.Idle
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

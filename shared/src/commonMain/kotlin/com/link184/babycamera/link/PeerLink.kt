package com.link184.babycamera.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * GATT service holding [BLE_PSM_CHARACTERISTIC_UUID]. Must match `babyCamServiceUUID` in the iOS
 * Swift code. Hosts don't advertise it: they advertise their own baby unit ID instead, so a parent
 * unit only finds the baby unit it was paired with.
 */
const val BLE_SERVICE_UUID = "6B1E0001-3C4F-4D8E-9A57-B5C2A1D0E7F3"

/**
 * Readable characteristic holding the host's L2CAP channel number (PSM), 2 bytes little-endian.
 * This is Apple's `CBUUIDL2CAPPSMCharacteristicString`.
 */
const val BLE_PSM_CHARACTERISTIC_UUID = "ABDD3056-28FA-441D-A470-55A75A52553A"

/**
 * How many parent units can listen to one baby unit at once. Each one costs ~35 kbps of the Bluetooth
 * link, and phones handle only a few simultaneous Bluetooth LE connections.
 */
const val MAX_LISTENERS = 4

enum class Role {
    /** Advertises and accepts up to [MAX_LISTENERS] joiners. In an Android + iPhone pair, the Android phone should host. */
    Host,

    /** Scans for a host and connects to it. */
    Join,
}

/** One connected phone. [id] is unique per connection, so a phone that reconnects gets a new one. */
data class Peer(val id: Int, val name: String)

sealed interface LinkState {
    data object Idle : LinkState

    data class Working(val step: String) : LinkState

    /** At least one peer is connected. A joiner has exactly one, its host. */
    data class Connected(val peers: List<Peer>) : LinkState

    data class Failed(val reason: String) : LinkState
}

/** What happens on each connection, in order: [Joined], any number of [Data], then [Left]. */
sealed interface PeerEvent {
    val peer: Peer

    data class Joined(override val peer: Peer) : PeerEvent

    /** Raw bytes received from [peer], in arbitrary chunks. */
    class Data(override val peer: Peer, val bytes: ByteArray) : PeerEvent

    data class Left(override val peer: Peer) : PeerEvent
}

/**
 * Router-less byte streams to nearby peers, implemented per platform over Bluetooth LE. A host
 * holds one stream per joiner; a joiner holds one stream to its host.
 */
interface PeerLink {
    val state: StateFlow<LinkState>

    /** Human-readable trace of every scan / connect / channel step. */
    val log: Flow<String>

    val events: Flow<PeerEvent>

    /** Signal strength of the host in dBm, when this side can measure it (only the joiner can). */
    val rssi: StateFlow<Int?>

    /** Whether this phone lets the app ask for the long-range (LE Coded) radio mode. */
    val supportsLongRange: Boolean

    /**
     * [unitId] is the baby unit's ID, a UUID: the host advertises it, the joiner looks for it.
     * See [PairingCode].
     */
    fun start(role: Role, longRange: Boolean, unitId: String)

    /** Suspends while [peer]'s connection is congested, so callers can't queue unbounded data. */
    suspend fun send(peer: Peer, bytes: ByteArray)

    /**
     * Queues [bytes] for every connected peer without waiting. A peer whose connection is congested
     * misses them, so one slow listener can't hold back the others.
     */
    fun broadcast(bytes: ByteArray)

    /** Drops the connection to [peer]. On a joiner that is the whole link, which then fails. */
    fun disconnect(peer: Peer)

    fun stop()
}

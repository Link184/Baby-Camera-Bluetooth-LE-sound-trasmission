package com.link184.babycamera.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** GATT service the host advertises. Must match `babyCamServiceUUID` in the iOS Swift code. */
const val BLE_SERVICE_UUID = "6B1E0001-3C4F-4D8E-9A57-B5C2A1D0E7F3"

/**
 * Readable characteristic holding the host's L2CAP channel number (PSM), 2 bytes little-endian.
 * This is Apple's `CBUUIDL2CAPPSMCharacteristicString`.
 */
const val BLE_PSM_CHARACTERISTIC_UUID = "ABDD3056-28FA-441D-A470-55A75A52553A"

enum class Role {
    /** Advertises and waits. In an Android + iPhone pair, the Android phone should host. */
    Host,

    /** Scans for a host and connects to it. */
    Join,
}

sealed interface LinkState {
    data object Idle : LinkState

    data class Working(val step: String) : LinkState

    data class Connected(val peer: String) : LinkState

    data class Failed(val reason: String) : LinkState
}

/** A router-less byte stream to one nearby peer, implemented per platform over Bluetooth LE. */
interface PeerLink {
    val state: StateFlow<LinkState>

    /** Human-readable trace of every scan / connect / channel step. */
    val log: Flow<String>

    /** Raw bytes received from the peer, in arbitrary chunks. */
    val incoming: Flow<ByteArray>

    /** Signal strength of the peer in dBm, when this side can measure it (only the joiner can). */
    val rssi: StateFlow<Int?>

    /** Whether this phone lets the app ask for the long-range (LE Coded) radio mode. */
    val supportsLongRange: Boolean

    fun start(role: Role, longRange: Boolean)

    /** Suspends while the link is congested, so callers can't queue unbounded data. */
    suspend fun send(bytes: ByteArray)

    fun stop()
}

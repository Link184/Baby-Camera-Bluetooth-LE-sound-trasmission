package com.link184.babycamera.watch

import com.google.android.gms.wearable.DataMap
import java.nio.ByteBuffer

/**
 * What the phone app and the watch app say to each other over the Wear OS Data Layer.
 *
 * KEEP IN SYNC: the same file is in app/ and wear/ (the modules share nothing else).
 *
 * - [STATE_PATH], a data item from the phone: the [State] of the parent unit. Data items are kept
 *   and synced, so a watch opening the app later still gets the latest one.
 * - [LEVEL_PATH], messages from the phone: the sound level, every [LEVEL_INTERVAL_MS], but only to
 *   watches that showed the app in the last [WATCHING_FOR_MS]. They also tell the watch the phone
 *   app is alive, so they keep coming while nothing is heard. To watches with their sound on they
 *   also carry the sound heard since the last one (see [encodeLevel]).
 * - [WATCHING_PATH], a message from the watch every [WATCHING_PING_MS] while it shows the app, and
 *   right away when its sound is switched on or off (see [encodeWatching]); [STOPPED_WATCHING_PATH]
 *   when it stops.
 * - [DISMISS_PATH], a message from the watch: the parent dismissed the crying alert there.
 */
object WatchProtocol {
    const val STATE_PATH = "/babycamera/state"
    const val LEVEL_PATH = "/babycamera/level"
    const val WATCHING_PATH = "/babycamera/watching"
    const val STOPPED_WATCHING_PATH = "/babycamera/stopped-watching"
    const val DISMISS_PATH = "/babycamera/dismiss"

    const val WATCHING_PING_MS = 10_000L
    const val WATCHING_FOR_MS = 25_000L
    const val LEVEL_INTERVAL_MS = 250L

    data class State(
        /** The phone is running as the parent unit. */
        val listening: Boolean,
        /** ...and is connected to the baby unit. */
        val connected: Boolean,
        /** One short line for the watch, e.g. "Listening". */
        val status: String,
        val crying: Boolean,
        /** The level that counts as loud, from 0 to 1, as on the phone's slider. */
        val threshold: Float,
    ) {
        fun writeTo(map: DataMap) {
            map.putBoolean(KEY_LISTENING, listening)
            map.putBoolean(KEY_CONNECTED, connected)
            map.putString(KEY_STATUS, status)
            map.putBoolean(KEY_CRYING, crying)
            map.putFloat(KEY_THRESHOLD, threshold)
        }

        companion object {
            fun from(map: DataMap) = State(
                listening = map.getBoolean(KEY_LISTENING),
                connected = map.getBoolean(KEY_CONNECTED),
                status = map.getString(KEY_STATUS) ?: "",
                crying = map.getBoolean(KEY_CRYING),
                threshold = map.getFloat(KEY_THRESHOLD),
            )
        }
    }

    /** Whether the watch wants the sound too. */
    fun encodeWatching(sound: Boolean): ByteArray = byteArrayOf(if (sound) 1 else 0)

    fun decodeWatching(bytes: ByteArray): Boolean = bytes.firstOrNull() == 1.toByte()

    /**
     * The sound level from 0 to 1, or null when there is none (not connected), as a 4-byte float;
     * then any [sound], already in the format of the shared code's `encodeSoundBatch`: sound packets
     * (`AudioPacket`s, oldest first), each as a 2-byte big-endian length and then the packet.
     */
    fun encodeLevel(level: Float?, sound: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(4 + sound.size).putFloat(level ?: Float.NaN).put(sound).array()

    fun decodeLevel(bytes: ByteArray): Float? =
        if (bytes.size < 4) null else ByteBuffer.wrap(bytes).float.takeUnless { it.isNaN() }

    /** The sound packets in a [LEVEL_PATH] message; empty for none. */
    fun decodeSound(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var at = 4
        while (at + 2 <= bytes.size) {
            val size = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
            if (at + 2 + size > bytes.size) break
            packets += bytes.copyOfRange(at + 2, at + 2 + size)
            at += 2 + size
        }
        return packets
    }

    private const val KEY_LISTENING = "listening"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_STATUS = "status"
    private const val KEY_CRYING = "crying"
    private const val KEY_THRESHOLD = "threshold"
}

package com.link184.babycamera.link

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What the baby unit's QR code holds: its unit ID, a random UUID made once and kept. The baby
 * unit advertises the ID over Bluetooth, and a parent unit that scanned it looks only for that,
 * so it never connects to a neighbour's baby unit. The ID is not a secret: the link is protected
 * by Bluetooth pairing.
 */
object PairingCode {
    private const val PREFIX = "babycamera:unit:"

    @OptIn(ExperimentalUuidApi::class)
    fun newUnitId(): String = Uuid.random().toString()

    fun format(unitId: String): String = PREFIX + unitId

    /** The unit ID in a scanned QR code, or null if it isn't a BabyCamera code. */
    @OptIn(ExperimentalUuidApi::class)
    fun parse(text: String): String? {
        val id = text.trim().removePrefix(PREFIX).takeIf { text.trim().startsWith(PREFIX) } ?: return null
        return try {
            Uuid.parse(id).toString()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Short form for showing on screen, e.g. "3F9A-C21B". */
    fun shortName(unitId: String): String =
        unitId.replace("-", "").takeLast(8).uppercase().chunked(4).joinToString("-")
}

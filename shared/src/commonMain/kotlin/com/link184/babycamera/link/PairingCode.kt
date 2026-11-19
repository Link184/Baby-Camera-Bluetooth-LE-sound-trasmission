package com.link184.babycamera.link

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What the baby unit's QR code holds: its unit ID and a secret key for one parent unit.
 *
 * - The unit ID is a random UUID made once and kept. The baby unit advertises it over Bluetooth,
 *   and a parent unit looks only for that, so it never connects to a neighbour's baby unit. It is
 *   not a secret.
 * - The key is the pre-shared key of the encrypted connection (see `SecureLink`). It is a secret:
 *   whoever has it can listen, until the parent unit is removed on the baby unit.
 */
object PairingCode {
    private const val PREFIX = "babycamera:2:"
    const val KEY_SIZE = 32

    class Content(val unitId: String, val key: ByteArray)

    @OptIn(ExperimentalUuidApi::class)
    fun newUnitId(): String = Uuid.random().toString()

    fun format(unitId: String, key: ByteArray): String {
        require(key.size == KEY_SIZE)
        return "$PREFIX$unitId:${base64.encode(key)}"
    }

    /** What a scanned QR code holds, or null if it isn't a BabyCamera code. */
    @OptIn(ExperimentalUuidApi::class)
    fun parse(text: String): Content? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val parts = trimmed.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return try {
            val unitId = Uuid.parse(parts[0]).toString()
            val key = base64.decode(parts[1])
            if (key.size == KEY_SIZE) Content(unitId, key) else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Short form for showing on screen, e.g. "3F9A-C21B". */
    fun shortName(unitId: String): String =
        unitId.replace("-", "").takeLast(8).uppercase().chunked(4).joinToString("-")

    @OptIn(ExperimentalEncodingApi::class)
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
}

package com.link184.babycamera.link

/**
 * Wire format on top of the byte stream: 4-byte big-endian length (type + payload), 1-byte type,
 * payload.
 */
class Frame(val type: Byte, val payload: ByteArray) {
    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out.putInt(0, payload.size + 1)
        out[4] = type
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    companion object {
        const val HEADER_SIZE = 5
        const val MAX_PAYLOAD = 4 * 1024 * 1024

        const val PING: Byte = 1
        const val PONG: Byte = 2
        const val BULK: Byte = 3
        const val BULK_END: Byte = 4
        const val BULK_RESULT: Byte = 5

        /** Sender's measured RSSI, 4-byte int. */
        const val RSSI: Byte = 6

        /** Simulated audio packet: 4-byte sequence number, then padding. */
        const val STREAM: Byte = 7

        /** Receiver's stream statistics as UTF-8 text, sent back to the stream sender. */
        const val STREAM_REPORT: Byte = 8
    }
}

/** Reassembles [Frame]s from arbitrarily chunked stream data. Not thread-safe. */
class FrameDecoder {
    private var buffer = ByteArray(64 * 1024)
    private var size = 0

    fun feed(chunk: ByteArray): List<Frame> {
        if (size + chunk.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size))
        }
        chunk.copyInto(buffer, size)
        size += chunk.size

        val frames = ArrayList<Frame>()
        var offset = 0
        while (size - offset >= Frame.HEADER_SIZE) {
            val length = buffer.getInt(offset)
            require(length in 1..Frame.MAX_PAYLOAD + 1) { "Corrupt stream: frame length $length" }
            if (size - offset < 4 + length) break
            val type = buffer[offset + 4]
            val payload = buffer.copyOfRange(offset + Frame.HEADER_SIZE, offset + 4 + length)
            frames += Frame(type, payload)
            offset += 4 + length
        }
        if (offset > 0) {
            buffer.copyInto(buffer, 0, offset, size)
            size -= offset
        }
        return frames
    }
}

internal fun ByteArray.putInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.getInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
        (this[offset + 1].toInt() and 0xFF shl 16) or
        (this[offset + 2].toInt() and 0xFF shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.putLong(offset: Int, value: Long) {
    putInt(offset, (value ushr 32).toInt())
    putInt(offset + 4, value.toInt())
}

internal fun ByteArray.getLong(offset: Int): Long =
    (getInt(offset).toLong() shl 32) or (getInt(offset + 4).toLong() and 0xFFFFFFFFL)

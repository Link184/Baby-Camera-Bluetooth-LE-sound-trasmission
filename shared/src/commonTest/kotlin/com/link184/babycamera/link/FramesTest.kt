package com.link184.babycamera.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FramesTest {

    @Test
    fun frameSurvivesEncodingAndDecoding() {
        val frame = Frame(Frame.AUDIO, byteArrayOf(1, 2, 3))
        val decoded = FrameDecoder().feed(frame.encode()).single()

        assertEquals(Frame.AUDIO, decoded.type)
        assertTrue(decoded.payload.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun emptyPayloadIsAllowed() {
        val decoded = FrameDecoder().feed(Frame(Frame.BULK_END, ByteArray(0)).encode()).single()
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun framesSplitIntoSingleBytesAreReassembled() {
        val bytes = Frame(Frame.PING, ByteArray(8) { it.toByte() }).encode() + Frame(Frame.PONG, ByteArray(3)).encode()
        val decoder = FrameDecoder()

        val frames = bytes.flatMap { decoder.feed(byteArrayOf(it)) }

        assertEquals(listOf(Frame.PING, Frame.PONG), frames.map { it.type })
        assertTrue(frames[0].payload.contentEquals(ByteArray(8) { it.toByte() }))
    }

    @Test
    fun severalFramesInOneChunkComeOutInOrder() {
        val chunk = (1..5).map { Frame(it.toByte(), ByteArray(it)).encode() }.reduce(ByteArray::plus)

        val frames = FrameDecoder().feed(chunk)

        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), frames.map { it.type })
        assertEquals(listOf(1, 2, 3, 4, 5), frames.map { it.payload.size })
    }

    @Test
    fun aFrameLargerThanTheStartingBufferWorks() {
        val big = ByteArray(200 * 1024) { (it % 251).toByte() }
        val encoded = Frame(Frame.BULK, big).encode()
        val decoder = FrameDecoder()

        val frames = encoded.toList().chunked(4096).flatMap { decoder.feed(it.toByteArray()) }

        assertTrue(frames.single().payload.contentEquals(big))
    }

    @Test
    fun impossibleLengthIsRejected() {
        assertFailsWith<IllegalArgumentException> { FrameDecoder().feed(ByteArray(Frame.HEADER_SIZE)) }
        val tooLong = ByteArray(Frame.HEADER_SIZE).also { it.putInt(0, Frame.MAX_PAYLOAD + 2) }
        assertFailsWith<IllegalArgumentException> { FrameDecoder().feed(tooLong) }
    }

    @Test
    fun numbersSurviveTheByteHelpers() {
        val bytes = ByteArray(12)
        bytes.putInt(0, -123_456)
        bytes.putLong(4, Long.MIN_VALUE + 42)

        assertEquals(-123_456, bytes.getInt(0))
        assertEquals(Long.MIN_VALUE + 42, bytes.getLong(4))
    }
}

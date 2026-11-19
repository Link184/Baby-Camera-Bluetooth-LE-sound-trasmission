package com.link184.babycamera.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioCodecTest {

    private fun tone(samples: Int, amplitude: Int, offset: Int = 0): ShortArray =
        ShortArray(samples) { (amplitude * sin(2 * PI * 440 * (it + offset) / AUDIO_SAMPLE_RATE)).toInt().toShort() }

    private fun rms(values: List<Int>): Double = sqrt(values.sumOf { it.toDouble() * it } / values.size)

    // ---- Loudness ----

    @Test
    fun silenceIsZeroAndFullScaleIsOne() {
        assertEquals(0f, soundLevel(ShortArray(320)))
        assertEquals(0f, soundLevel(ShortArray(0)))
        assertEquals(1f, soundLevel(ShortArray(320) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }))
    }

    @Test
    fun louderSoundHasAHigherLevel() {
        val quiet = soundLevel(tone(320, 300))
        val medium = soundLevel(tone(320, 3_000))
        val loud = soundLevel(tone(320, 30_000))

        assertTrue(quiet < medium && medium < loud, "$quiet < $medium < $loud")
    }

    // ---- Packets ----

    @Test
    fun packetKeepsItsSequenceNumberLevelAndLength() {
        val pcm = tone(AUDIO_FRAME_SAMPLES, 10_000)
        val packet = AudioPacket.decode(AudioPacket.encode(42, pcm, AdpcmState()))

        assertNotNull(packet)
        assertEquals(42, packet.seq)
        assertEquals(AUDIO_FRAME_SAMPLES, packet.pcm.size)
        assertEquals(soundLevel(pcm), packet.level, absoluteTolerance = 1 / 255f)
    }

    @Test
    fun decodedSoundIsCloseToTheOriginal() {
        val pcm = tone(AUDIO_FRAME_SAMPLES, 10_000)
        val decoded = AudioPacket.decode(AudioPacket.encode(0, pcm, AdpcmState()))!!.pcm

        // ADPCM needs a few samples to adapt from silence, so judge the second half.
        val half = AUDIO_FRAME_SAMPLES / 2 until AUDIO_FRAME_SAMPLES
        val error = rms(half.map { decoded[it] - pcm[it] })
        val signal = rms(half.map { pcm[it].toInt() })
        assertTrue(error < signal * 0.15, "error $error vs signal $signal")
    }

    @Test
    fun aPacketDecodesWithoutThePacketsBeforeIt() {
        // A listener that joins late, or missed packets, starts mid-stream.
        val encoder = AdpcmState()
        AudioPacket.encode(0, tone(AUDIO_FRAME_SAMPLES, 10_000), encoder)
        val second = tone(AUDIO_FRAME_SAMPLES, 10_000, offset = AUDIO_FRAME_SAMPLES)
        val decoded = AudioPacket.decode(AudioPacket.encode(1, second, encoder))!!.pcm

        // Right from its first samples, no adapting needed.
        val start = 0 until 40
        val error = rms(start.map { decoded[it] - second[it] })
        val signal = rms(start.map { second[it].toInt() })
        assertTrue(error < signal * 0.15, "error $error vs signal $signal")
    }

    @Test
    fun truncatedPacketIsRejected() {
        assertNull(AudioPacket.decode(ByteArray(7)))
    }

    // ---- Resampling into frames ----

    @Test
    fun microphoneAt48kHzBecomes8kHzFrames() {
        val frames = mutableListOf<ShortArray>()
        val assembler = FrameAssembler { frames += it }

        // A second and a bit: 8 100 samples out, so rounding in the resampler can't lose a frame.
        assembler.add(ShortArray(48_600) { 1_000 }, 48_600, 48_000)

        assertEquals(25, frames.size, "25 frames of 40 ms")
        assertTrue(frames.all { frame -> frame.all { it.toInt() == 1_000 } })
    }

    @Test
    fun framesFillUpAcrossCalls() {
        val frames = mutableListOf<ShortArray>()
        val assembler = FrameAssembler { frames += it }

        assembler.add(ShortArray(200) { 1 }, 200, AUDIO_SAMPLE_RATE)
        assertTrue(frames.isEmpty())
        assembler.add(ShortArray(500) { 2 }, 150, AUDIO_SAMPLE_RATE) // only the first 150 count

        assertEquals(1, frames.size)
        assertEquals(1, frames[0][199].toInt())
        assertEquals(2, frames[0][200].toInt())
    }

    // ---- Crying detection ----

    @Test
    fun loudOnlyAfterEnoughLoudFramesInTheWindow() {
        val detector = LoudnessDetector(windowFrames = 5, neededFrames = 3)

        assertEquals(listOf(false, false, false, true), listOf(true, false, true, true).map(detector::onFrame))
    }

    @Test
    fun oldLoudFramesFallOutOfTheWindow() {
        val detector = LoudnessDetector(windowFrames = 3, neededFrames = 2)
        detector.onFrame(true)
        detector.onFrame(true)

        assertEquals(listOf(true, false, false), listOf(false, false, false).map(detector::onFrame))
    }

    @Test
    fun resetForgetsEverything() {
        val detector = LoudnessDetector(windowFrames = 3, neededFrames = 1)
        detector.onFrame(true)
        detector.reset()

        assertEquals(false, detector.onFrame(false))
    }
}

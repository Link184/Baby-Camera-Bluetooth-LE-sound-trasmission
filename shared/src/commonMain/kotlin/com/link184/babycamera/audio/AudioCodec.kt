package com.link184.babycamera.audio

import com.link184.babycamera.link.getInt
import com.link184.babycamera.link.putInt
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything travels as 8 kHz mono: plenty for a baby monitor, and light enough for Bluetooth LE. */
const val AUDIO_SAMPLE_RATE = 8_000

/** 40 ms of sound per packet. */
const val AUDIO_FRAME_SAMPLES = 320
const val AUDIO_FRAME_MS = AUDIO_FRAME_SAMPLES * 1_000L / AUDIO_SAMPLE_RATE

/**
 * IMA ADPCM: 4 bits per sample, so 8 kHz sound needs 32 kbps. Simple enough to run the same code
 * on both platforms. A stronger codec (Opus, ~12 kbps) can replace it later if long range needs it.
 */
internal class AdpcmState(var predictor: Int = 0, var index: Int = 0) {

    fun encode(sample: Int): Int {
        val step = STEPS[index]
        var diff = sample - predictor
        var nibble = 0
        if (diff < 0) {
            nibble = 8
            diff = -diff
        }
        var delta = step shr 3
        if (diff >= step) {
            nibble = nibble or 4
            diff -= step
            delta += step
        }
        if (diff >= step shr 1) {
            nibble = nibble or 2
            diff -= step shr 1
            delta += step shr 1
        }
        if (diff >= step shr 2) {
            nibble = nibble or 1
            delta += step shr 2
        }
        advance(nibble, delta)
        return nibble
    }

    fun decode(nibble: Int): Short {
        val step = STEPS[index]
        var delta = step shr 3
        if (nibble and 4 != 0) delta += step
        if (nibble and 2 != 0) delta += step shr 1
        if (nibble and 1 != 0) delta += step shr 2
        advance(nibble, delta)
        return predictor.toShort()
    }

    private fun advance(nibble: Int, delta: Int) {
        predictor = (if (nibble and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
        index = (index + INDEX_CHANGES[nibble]).coerceIn(0, STEPS.lastIndex)
    }

    private companion object {
        val INDEX_CHANGES = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
        val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
            449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
            9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
            32767,
        )
    }
}

/**
 * One packet of sound. Wire format: 4-byte sequence number, 1-byte sound level (0-255), the ADPCM
 * state at the start of the packet (2-byte predictor, 1-byte step index), then 4-bit samples, low
 * nibble first. Carrying the state makes every packet decodable on its own, e.g. after a reconnect.
 */
class AudioPacket(val seq: Int, val level: Float, val pcm: ShortArray) {

    companion object {
        private const val HEADER = 8

        internal fun encode(seq: Int, pcm: ShortArray, state: AdpcmState): ByteArray {
            val out = ByteArray(HEADER + pcm.size / 2)
            out.putInt(0, seq)
            out[4] = (soundLevel(pcm) * 255).roundToInt().toByte()
            out[5] = (state.predictor shr 8).toByte()
            out[6] = state.predictor.toByte()
            out[7] = state.index.toByte()
            for (i in 0 until pcm.size / 2) {
                val low = state.encode(pcm[2 * i].toInt())
                val high = state.encode(pcm[2 * i + 1].toInt())
                out[HEADER + i] = (low or (high shl 4)).toByte()
            }
            return out
        }

        fun decode(payload: ByteArray): AudioPacket? {
            if (payload.size < HEADER) return null
            val predictor = ((payload[5].toInt() shl 8) or (payload[6].toInt() and 0xFF)).toShort().toInt()
            val state = AdpcmState(predictor, (payload[7].toInt() and 0xFF).coerceIn(0, 88))
            val pcm = ShortArray((payload.size - HEADER) * 2)
            for (i in 0 until payload.size - HEADER) {
                val byte = payload[HEADER + i].toInt()
                pcm[2 * i] = state.decode(byte and 0x0F)
                pcm[2 * i + 1] = state.decode((byte shr 4) and 0x0F)
            }
            return AudioPacket(payload.getInt(0), levelOf(payload), pcm)
        }

        fun levelOf(payload: ByteArray): Float = (payload[4].toInt() and 0xFF) / 255f
    }
}

/**
 * Loudness from 0 (quiet room) to 1 (very loud), on a decibel scale so the slider feels even:
 * [QUIET_DB] dBFS and below is 0, [LOUD_DB] dBFS and above is 1.
 */
fun soundLevel(pcm: ShortArray): Float {
    if (pcm.isEmpty()) return 0f
    var sum = 0.0
    for (sample in pcm) sum += sample.toDouble() * sample
    val rms = sqrt(sum / pcm.size)
    if (rms < 1.0) return 0f
    val db = 20 * log10(rms / 32768.0)
    return ((db - QUIET_DB) / (LOUD_DB - QUIET_DB)).toFloat().coerceIn(0f, 1f)
}

private const val QUIET_DB = -60.0
private const val LOUD_DB = -10.0

/**
 * Turns microphone samples at any rate of at least 8 kHz into 8 kHz frames of
 * [AUDIO_FRAME_SAMPLES]. Downsamples by averaging, which also filters out most of what 8 kHz can't
 * carry. Use from one thread only.
 */
class FrameAssembler(private val onFrame: (ShortArray) -> Unit) {
    private val frame = ShortArray(AUDIO_FRAME_SAMPLES)
    private var filled = 0
    private var phase = 0.0
    private var sum = 0.0
    private var summed = 0

    fun add(samples: ShortArray, count: Int, sampleRate: Int) {
        if (sampleRate <= AUDIO_SAMPLE_RATE) {
            for (i in 0 until count) push(samples[i])
            return
        }
        val step = AUDIO_SAMPLE_RATE.toDouble() / sampleRate
        for (i in 0 until count) {
            sum += samples[i]
            summed++
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                push((sum / summed).roundToInt().toShort())
                sum = 0.0
                summed = 0
            }
        }
    }

    private fun push(sample: Short) {
        frame[filled++] = sample
        if (filled == frame.size) {
            onFrame(frame.copyOf())
            filled = 0
        }
    }
}

/** True once at least [neededFrames] of the last [windowFrames] frames were loud. */
internal class LoudnessDetector(windowFrames: Int, private val neededFrames: Int) {
    private val window = BooleanArray(windowFrames)
    private var position = 0
    private var loud = 0

    fun reset() {
        window.fill(false)
        position = 0
        loud = 0
    }

    fun onFrame(isLoud: Boolean): Boolean {
        if (window[position]) loud--
        window[position] = isLoud
        if (isLoud) loud++
        position = (position + 1) % window.size
        return loud >= neededFrames
    }
}

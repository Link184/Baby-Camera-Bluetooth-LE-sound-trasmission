package com.link184.babycamera.wear

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the baby unit's sound, relayed by the phone app, on the watch's speaker (or its Bluetooth
 * headphones). The phone sends it in bursts 4 times a second, so playback waits for [PREFILL_SAMPLES]
 * before it starts. Main thread only.
 */
class SoundPlayer {
    private var track: AudioTrack? = null
    private var written = 0
    private var started = false

    fun start() {
        if (track != null) return
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBytes, BUFFER_SAMPLES * 2))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the sound: ${e.message}")
            null
        }
        written = 0
        started = false
    }

    fun stop() {
        track?.run {
            stop()
            release()
        }
        track = null
    }

    /** One sound packet as the baby unit sent it; dropped if too much is already waiting. */
    fun play(packet: ByteArray) {
        val track = track ?: return
        val pcm = AdpcmPacket.decode(packet) ?: return
        written += track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0)
        if (!started && written >= PREFILL_SAMPLES) {
            track.play()
            started = true
        }
    }

    private companion object {
        const val TAG = "SoundPlayer"
        const val SAMPLE_RATE = 8_000
        const val PREFILL_SAMPLES = SAMPLE_RATE / 2
        const val BUFFER_SAMPLES = SAMPLE_RATE * 3 / 2
    }
}

/**
 * Decodes the baby unit's sound packets: the shared code's AudioPacket and IMA ADPCM, which the
 * watch app can't use from there (it shares only WatchProtocol.kt with the phone app). KEEP IN SYNC
 * with shared/.../audio/AudioCodec.kt. Format: 4-byte sequence number, 1-byte level, the ADPCM state
 * (2-byte predictor, 1-byte step index), then 4-bit samples, low nibble first.
 */
internal object AdpcmPacket {
    private const val HEADER = 8

    fun decode(payload: ByteArray): ShortArray? {
        if (payload.size < HEADER) return null
        var predictor = ((payload[5].toInt() shl 8) or (payload[6].toInt() and 0xFF)).toShort().toInt()
        var index = (payload[7].toInt() and 0xFF).coerceIn(0, STEPS.lastIndex)
        val pcm = ShortArray((payload.size - HEADER) * 2)
        for (i in pcm.indices) {
            val byte = payload[HEADER + i / 2].toInt()
            val nibble = if (i % 2 == 0) byte and 0x0F else (byte shr 4) and 0x0F
            val step = STEPS[index]
            var delta = step shr 3
            if (nibble and 4 != 0) delta += step
            if (nibble and 2 != 0) delta += step shr 1
            if (nibble and 1 != 0) delta += step shr 2
            predictor = (if (nibble and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
            index = (index + INDEX_CHANGES[nibble]).coerceIn(0, STEPS.lastIndex)
            pcm[i] = predictor.toShort()
        }
        return pcm
    }

    private val INDEX_CHANGES = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEPS = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
        449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
        9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767,
    )
}

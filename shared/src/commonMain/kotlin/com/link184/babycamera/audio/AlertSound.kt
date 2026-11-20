package com.link184.babycamera.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * What the parent unit plays when it suddenly loses the baby unit: a falling two-note chime, twice,
 * about a second at [AUDIO_SAMPLE_RATE]. It sounds nothing like a baby, so it isn't taken for the
 * stream. Made up here rather than loaded from a file, so both platforms play the same sound
 * through the same speaker as the baby's sound, at the same volume.
 */
object AlertSound {

    /** 16-bit mono samples, padded with silence to whole [AUDIO_FRAME_SAMPLES] frames. */
    val connectionLost: ShortArray by lazy { connectionLostSamples() }

    private fun connectionLostSamples(): ShortArray {
        val notes = listOf(HIGH_HZ to HIGH_MS, LOW_HZ to LOW_MS, 0.0 to GAP_MS)
        val once = notes.flatMap { (hz, ms) -> note(hz, ms).asList() }
        val sound = once + once
        val frames = (sound.size + AUDIO_FRAME_SAMPLES - 1) / AUDIO_FRAME_SAMPLES
        return ShortArray(frames * AUDIO_FRAME_SAMPLES) { if (it < sound.size) sound[it] else 0 }
    }

    /** A bell-like note: quick fade in, then dying away, with a soft second harmonic. Silence for 0 Hz. */
    private fun note(hz: Double, ms: Int): ShortArray {
        val count = AUDIO_SAMPLE_RATE * ms / 1_000
        if (hz == 0.0) return ShortArray(count)
        val attack = AUDIO_SAMPLE_RATE * ATTACK_MS / 1_000
        return ShortArray(count) { i ->
            val t = i.toDouble() / AUDIO_SAMPLE_RATE
            val envelope = min(1.0, i.toDouble() / attack) * exp(-DECAY_PER_SECOND * t) *
                min(1.0, (count - i).toDouble() / attack) // no click at the end
            val wave = sin(2 * PI * hz * t) + 0.3 * sin(4 * PI * hz * t)
            (AMPLITUDE * envelope * wave / 1.3).toInt().toShort()
        }
    }

    private const val HIGH_HZ = 1_319.0 // E6
    private const val LOW_HZ = 880.0 // A5
    private const val HIGH_MS = 180
    private const val LOW_MS = 260
    private const val GAP_MS = 120
    private const val ATTACK_MS = 8
    private const val DECAY_PER_SECOND = 6.0
    private const val AMPLITUDE = 0.7 * Short.MAX_VALUE
}

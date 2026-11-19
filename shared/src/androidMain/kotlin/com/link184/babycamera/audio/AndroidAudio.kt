package com.link184.babycamera.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.link184.babycamera.AudioInput
import com.link184.babycamera.AudioOutput

/** Microphone through [AudioRecord], read on its own thread. */
class AndroidAudioInput(private val context: Context) : AudioInput {

    @Volatile
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked just below
    override fun start(onSamples: (samples: ShortArray, count: Int, sampleRate: Int) -> Unit): String? {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Microphone permission not granted"
        }
        // 8 kHz works on nearly every phone; the others are fallbacks, resampled to 8 kHz.
        for (rate in intArrayOf(AUDIO_SAMPLE_RATE, 16_000, 48_000, 44_100)) {
            val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) continue
            val created = try {
                // VOICE_RECOGNITION has no automatic gain, so the level the parent sees is stable.
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes, rate / 2), // 250 ms
                )
            } catch (e: IllegalArgumentException) {
                null
            } ?: continue
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                continue
            }
            try {
                created.startRecording()
            } catch (e: IllegalStateException) {
                created.release()
                return "Cannot start the microphone: ${e.message}"
            }
            record = created
            thread = Thread({ readLoop(created, rate, onSamples) }, "microphone").apply { start() }
            return null
        }
        return "Cannot open the microphone"
    }

    private fun readLoop(source: AudioRecord, rate: Int, onSamples: (ShortArray, Int, Int) -> Unit) {
        val buffer = ShortArray(rate / 25) // 40 ms
        while (record === source) {
            val read = source.read(buffer, 0, buffer.size)
            if (read > 0) onSamples(buffer, read, rate) else if (read < 0) break
        }
    }

    override fun stop() {
        val current = record ?: return
        record = null
        try {
            current.stop()
        } catch (_: IllegalStateException) {
        }
        thread?.join(500)
        thread = null
        current.release()
    }
}

/** Speaker through a streaming [AudioTrack] holding up to a second of sound. */
class AndroidAudioOutput : AudioOutput {

    private var track: AudioTrack? = null

    override fun start(sampleRate: Int): String? {
        stop()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 2))
                .build()
                .also { it.play() }
            null
        } catch (e: Exception) {
            stop()
            "Cannot start the speaker: ${e.message}"
        }
    }

    override fun write(samples: ShortArray) {
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun stop() {
        val current = track ?: return
        track = null
        try {
            current.stop()
        } catch (_: IllegalStateException) {
        }
        current.release()
    }
}

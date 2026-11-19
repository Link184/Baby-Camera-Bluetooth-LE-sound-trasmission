package com.link184.babycamera.audio

import com.link184.babycamera.AudioInput
import com.link184.babycamera.AudioOutput
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive

/**
 * Microphone through AVAudioEngine. Runs in the background thanks to the "audio" background mode,
 * as long as it was started in the foreground. iOS asks for the microphone permission itself.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioInput : AudioInput {

    private var engine: AVAudioEngine? = null

    override fun start(onSamples: (samples: ShortArray, count: Int, sampleRate: Int) -> Unit): String? {
        stop()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, error = null)
        session.setMode(AVAudioSessionModeMeasurement, error = null) // no automatic gain
        if (!session.setActive(true, error = null)) return "Cannot use the microphone right now"

        val engine = AVAudioEngine()
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        val rate = format.sampleRate.toInt()
        if (rate < AUDIO_SAMPLE_RATE || format.channelCount == 0u) {
            return "Microphone unavailable - allow it in Settings > Privacy > Microphone"
        }
        input.installTapOnBus(0u, bufferSize = 4096u, format = format) { buffer, _ ->
            val samples = buffer?.floatChannelData?.get(0) ?: return@installTapOnBus
            val count = buffer.frameLength.toInt()
            val pcm = ShortArray(count) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
            onSamples(pcm, count, rate)
        }
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            return "Cannot start the microphone"
        }
        this.engine = engine
        return null
    }

    override fun stop() {
        val current = engine ?: return
        engine = null
        current.inputNode.removeTapOnBus(0u)
        current.stop()
    }
}

/** Speaker through an AVAudioPlayerNode; the engine's mixer converts 8 kHz to the hardware rate. */
@OptIn(ExperimentalForeignApi::class)
class IosAudioOutput : AudioOutput {

    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayerNode? = null
    private var format: AVAudioFormat? = null

    override fun start(sampleRate: Int): String? {
        stop()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        if (!session.setActive(true, error = null)) return "Cannot use the speaker right now"

        val engine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        val format = AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = sampleRate.toDouble(),
            channels = 1u,
            interleaved = false,
        )
        engine.attachNode(player)
        engine.connect(player, to = engine.mainMixerNode, format = format)
        engine.prepare()
        if (!engine.startAndReturnError(null)) return "Cannot start the speaker"
        player.play()
        this.engine = engine
        this.player = player
        this.format = format
        return null
    }

    override fun write(samples: ShortArray) {
        val player = player ?: return
        val format = format ?: return
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = samples.size.toUInt())
        buffer.frameLength = samples.size.toUInt()
        val out = buffer.floatChannelData?.get(0) ?: return
        for (i in samples.indices) out[i] = samples[i] / 32768f
        player.scheduleBuffer(buffer, completionHandler = null)
    }

    override fun stop() {
        player?.stop()
        engine?.stop()
        player = null
        engine = null
        format = null
    }
}

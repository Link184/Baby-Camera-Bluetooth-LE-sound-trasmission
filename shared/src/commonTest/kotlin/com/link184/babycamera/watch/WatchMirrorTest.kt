package com.link184.babycamera.watch

import com.link184.babycamera.FakeMicrophone
import com.link184.babycamera.FakePeerLink
import com.link184.babycamera.FakeSecretStore
import com.link184.babycamera.FakeSpeaker
import com.link184.babycamera.FakeSystem
import com.link184.babycamera.MonitorController
import com.link184.babycamera.MonitorRole
import com.link184.babycamera.audio.AUDIO_FRAME_SAMPLES
import com.link184.babycamera.audio.AdpcmState
import com.link184.babycamera.audio.AudioPacket
import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.PairingCode
import com.link184.babycamera.link.Peer
import com.link184.babycamera.security.Pairings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchMirrorTest {

    private class FakeWatch : WatchLink {
        val published = mutableListOf<WatchState>()
        var result = WatchPublish.Sent
        override var watching = false
        override var wantsSound = false
        val levels = mutableListOf<Float?>()
        val sound = mutableListOf<List<ByteArray>>()
        var onDismiss: (() -> Unit)? = null
        var stopped = false

        override fun start(onDismiss: () -> Unit) {
            this.onDismiss = onDismiss
        }

        override fun stop() {
            stopped = true
        }

        override suspend fun publish(state: WatchState): WatchPublish {
            published += state
            return result
        }

        override suspend fun sendLevel(level: Float?, sound: List<ByteArray>) {
            levels += level
            this.sound += sound
        }
    }

    private class Phone(scope: TestScope) {
        val link = FakePeerLink()
        val system = FakeSystem()
        val controller = MonitorController(
            link, FakeMicrophone(), FakeSpeaker(), system, Pairings(FakeSecretStore()),
            scope.backgroundScope, scope.testScheduler.timeSource,
        )
        val watch = FakeWatch()

        init {
            WatchMirror(controller, watch, scope.backgroundScope).start()
        }

        fun startParent() {
            controller.pairWith(PairingCode.format("0f8fad5b-d9cb-469f-a165-70867728950e", ByteArray(32)))
            controller.start(MonitorRole.Parent)
        }
    }

    private val babyUnit = Peer(7, "baby")

    @Test
    fun theStateIsSentOnlyWhenItChanges() = runTest {
        val phone = Phone(this)
        runCurrent()
        assertEquals(listOf("Not listening"), phone.watch.published.map { it.status })

        advanceTimeBy(10_000)
        assertEquals(1, phone.watch.published.size)

        phone.startParent()
        advanceTimeBy(1_001)
        phone.link.join(babyUnit)
        advanceTimeBy(1_001)

        val (_, connecting, listening) = phone.watch.published
        assertEquals("Connecting to the baby unit", connecting.status)
        assertFalse(connecting.connected)
        assertEquals("Listening", listening.status)
        assertTrue(listening.listening && listening.connected)
        assertEquals(phone.controller.threshold, listening.threshold)
    }

    @Test
    fun aStateThatCouldntBeSentIsTriedAgain() = runTest {
        val phone = Phone(this)
        phone.watch.result = WatchPublish.Retry
        runCurrent()
        advanceTimeBy(3_001)
        assertEquals(4, phone.watch.published.size)

        phone.watch.result = WatchPublish.Sent
        advanceTimeBy(5_000)
        assertEquals(5, phone.watch.published.size, "sent once, then left alone")
    }

    @Test
    fun aPhoneThatCantHaveAWatchAppStopsTrying() = runTest {
        val phone = Phone(this)
        phone.watch.result = WatchPublish.Unsupported
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(1, phone.watch.published.size)
        assertTrue(phone.watch.stopped)
    }

    @Test
    fun theLevelGoesOutOnlyWhileAWatchShowsIt() = runTest {
        val phone = Phone(this)
        phone.startParent()
        phone.link.join(babyUnit)
        runCurrent()
        phone.link.receive(babyUnit, audioFrame(amplitude = 8_000))
        advanceTimeBy(5_000)
        assertTrue(phone.watch.levels.isEmpty())

        phone.watch.watching = true
        advanceTimeBy(1_000)
        assertEquals(4, phone.watch.levels.size, "every 250 ms")
        assertNotNull(phone.watch.levels.last())

        phone.watch.watching = false
        advanceTimeBy(5_000)
        assertEquals(4, phone.watch.levels.size)
    }

    @Test
    fun theSoundGoesOutOnlyToAWatchThatWantsIt() = runTest {
        val phone = Phone(this)
        phone.startParent()
        phone.link.join(babyUnit)
        phone.watch.watching = true
        runCurrent()
        phone.link.receive(babyUnit, audioFrame(amplitude = 8_000, seq = 0))
        advanceTimeBy(300)
        assertTrue(phone.watch.sound.all { it.isEmpty() }, "sound off on the watch")

        phone.watch.wantsSound = true
        val frames = (1..3).map { audioFrame(amplitude = 8_000, seq = it) }
        frames.forEach { phone.link.receive(babyUnit, it) }
        advanceTimeBy(300)
        val sent = phone.watch.sound.flatten()
        assertEquals(frames.map { it.payload.toList() }, sent.map { it.toList() }, "each packet once, in order")
    }

    @Test
    fun soundBatchesAreLengthPrefixed() {
        val batch = encodeSoundBatch(listOf(byteArrayOf(1, 2, 3), ByteArray(300) { 9 }))
        assertEquals(2 + 3 + 2 + 300, batch.size)
        assertEquals(listOf<Byte>(0, 3, 1, 2, 3, 1, 44), batch.take(7))
    }

    @Test
    fun aPhoneThatIsntListeningSendsNoLevel() = runTest {
        val phone = Phone(this)
        phone.watch.watching = true
        runCurrent()
        advanceTimeBy(500)
        assertTrue(phone.watch.levels.isNotEmpty(), "still sent, so the watch knows the phone app is there")
        assertTrue(phone.watch.levels.all { it == null })
    }

    @Test
    fun cryingShowsOnTheWatchAndCanBeDismissedThere() = runTest {
        val phone = Phone(this)
        phone.startParent()
        phone.link.join(babyUnit)
        repeat(1_000 / 40) { phone.link.receive(babyUnit, audioFrame(amplitude = 30_000, seq = it)) }
        advanceTimeBy(1_001)
        assertTrue(phone.watch.published.last().crying)

        assertNotNull(phone.watch.onDismiss).invoke()
        advanceTimeBy(1_001)

        assertFalse(phone.controller.crying)
        assertFalse(phone.watch.published.last().crying)
    }

    @Test
    fun theBabyUnitSaysSo() = runTest {
        val phone = Phone(this)
        phone.controller.start(MonitorRole.Baby)
        advanceTimeBy(1_001)
        val state = phone.watch.published.last()
        assertEquals("This phone is the baby unit", state.status)
        assertFalse(state.listening)
        assertNull(phone.watch.levels.lastOrNull())
    }

    private fun audioFrame(amplitude: Int, seq: Int = 0): Frame {
        val samples = ShortArray(AUDIO_FRAME_SAMPLES) { (amplitude * sin(2 * PI * 440 * it / 8_000)).toInt().toShort() }
        return Frame(Frame.AUDIO, AudioPacket.encode(seq, samples, AdpcmState()))
    }
}

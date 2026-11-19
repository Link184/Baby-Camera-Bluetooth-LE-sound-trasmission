package com.link184.babycamera

import com.link184.babycamera.audio.AUDIO_FRAME_SAMPLES
import com.link184.babycamera.audio.AdpcmState
import com.link184.babycamera.audio.AudioPacket
import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.PairingCode
import com.link184.babycamera.link.Peer
import com.link184.babycamera.link.Role
import com.link184.babycamera.link.getInt
import com.link184.babycamera.link.putInt
import com.link184.babycamera.link.putLong
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
class MonitorControllerTest {

    private val parentA = Peer(1, "parent-a")
    private val parentB = Peer(2, "parent-b")
    private val babyUnit = Peer(7, "baby")
    private val someUnitId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    /** One phone running the controller, with fakes around it, on the test's virtual clock. */
    private class Phone(
        scope: TestScope,
        val system: FakeSystem = FakeSystem(),
        val link: FakePeerLink = FakePeerLink(),
    ) {
        val microphone = FakeMicrophone()
        val speaker = FakeSpeaker()
        val controller = MonitorController(link, microphone, speaker, system, scope.backgroundScope, scope.testScheduler.timeSource)
    }

    // ---- Baby unit ID and pairing ----

    @Test
    fun babyUnitAdvertisesItsOwnIdWhichStaysTheSame() = runTest {
        val phone = Phone(this)
        phone.controller.start(MonitorRole.Baby)

        val id = phone.controller.ownUnitId
        assertEquals(FakePeerLink.Start(Role.Host, false, id), phone.link.starts.single())
        assertEquals(id, Phone(this, phone.system).controller.ownUnitId, "kept across restarts")
        assertEquals(id, PairingCode.parse(phone.controller.pairingCode))
    }

    @Test
    fun parentUnitWontStartBeforeScanningTheBabyUnit() = runTest {
        val phone = Phone(this)
        phone.controller.start(MonitorRole.Parent)

        assertNotNull(phone.controller.startError)
        assertNull(phone.controller.session)
        assertTrue(phone.link.starts.isEmpty())
        assertFalse(phone.speaker.running)
    }

    @Test
    fun parentUnitLooksForTheScannedBabyUnitAfterARestart() = runTest {
        val phone = Phone(this)
        assertFalse(phone.controller.pairWith("https://example.com"))
        assertNull(phone.controller.pairedUnitId)
        assertTrue(phone.controller.pairWith(PairingCode.format(someUnitId)))

        val restarted = Phone(this, phone.system)
        assertEquals(someUnitId, restarted.controller.pairedUnitId)
        restarted.controller.start(MonitorRole.Parent)
        assertEquals(FakePeerLink.Start(Role.Join, false, someUnitId), restarted.link.starts.single())
        assertTrue(restarted.speaker.running)
    }

    @Test
    fun microphoneProblemIsShownAndNothingStarts() = runTest {
        val phone = Phone(this)
        phone.microphone.startError = "no microphone"
        phone.controller.start(MonitorRole.Baby)

        assertEquals("no microphone", phone.controller.startError)
        assertNull(phone.controller.session)
        assertTrue(phone.link.starts.isEmpty())
    }

    // ---- Baby unit with several listeners ----

    @Test
    fun babyUnitSendsTheSoundToEveryListener() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.join(parentB)
        runCurrent()

        phone.microphone.hear(tone(amplitude = 8_000))
        runCurrent()

        val packet = AudioPacket.decode(phone.link.broadcasts(Frame.AUDIO).single().payload)
        assertNotNull(packet)
        assertEquals(0, packet.seq)
        assertNotNull(phone.controller.level)
    }

    @Test
    fun babyUnitDoesntSendSoundWhileNobodyListens() = runTest {
        val phone = startedBaby()
        phone.microphone.hear(tone(amplitude = 8_000))
        runCurrent()

        assertTrue(phone.link.broadcasts.isEmpty())
        assertNotNull(phone.controller.level, "the meter still moves")
    }

    @Test
    fun repliesToEachPeersPingOnlyToThatPeer() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.join(parentB)
        val ping = Frame(Frame.PING, ByteArray(8).also { it.putLong(0, 1234) })
        phone.link.receive(parentA, ping)
        runCurrent()

        assertTrue(phone.link.sentTo(parentA, Frame.PONG).single().payload.contentEquals(ping.payload))
        assertTrue(phone.link.sentTo(parentB, Frame.PONG).isEmpty())
    }

    @Test
    fun measuresTheRoundTripToEachPeer() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        runCurrent()

        advanceTimeBy(1_001)
        val ping = phone.link.broadcasts(Frame.PING).single()
        advanceTimeBy(30)
        phone.link.receive(parentA, Frame(Frame.PONG, ping.payload))
        runCurrent()

        // Pinged at 1000 ms, answered at 1031 ms.
        assertEquals(31.0, phone.controller.rttMs.getValue(parentA.id), absoluteTolerance = 0.5)
    }

    @Test
    fun aListenerLeavingCountsAsADropAndForgetsItsStats() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.join(parentB)
        phone.link.receive(parentA, Frame(Frame.RSSI, ByteArray(4).also { it.putInt(0, -60) }))
        runCurrent()
        assertEquals(-60, phone.controller.peerRssi[parentA.id])

        phone.link.leave(parentA)
        runCurrent()

        assertEquals(1, phone.controller.drops)
        assertNull(phone.controller.peerRssi[parentA.id])
        assertEquals("Connected - 1 parent unit is listening", phone.system.statuses.last())
    }

    @Test
    fun corruptDataDropsOnlyThatListener() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.join(parentB)
        phone.link.receiveRaw(parentA, ByteArray(Frame.HEADER_SIZE)) // length 0 is impossible
        runCurrent()

        assertEquals(listOf(parentA), phone.link.disconnected)
        assertEquals("Connected - 1 parent unit is listening", phone.system.statuses.last())
    }

    @Test
    fun statusCountsTheListeners() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.join(parentB)
        runCurrent()

        assertEquals("Connected - 2 parent units are listening", phone.system.statuses.last())
    }

    // ---- Parent unit ----

    @Test
    fun parentUnitPlaysWhatItReceives() = runTest {
        val phone = startedParent()
        phone.link.join(babyUnit)
        phone.link.receive(babyUnit, audioFrame(seq = 0, amplitude = 3_000))
        runCurrent()

        assertEquals(AUDIO_FRAME_SAMPLES, phone.speaker.played.single().size)
        assertNotNull(phone.controller.level)
    }

    @Test
    fun alertsOnceTheSoundStaysLoudForASecond() = runTest {
        val phone = startedParent()
        phone.link.join(babyUnit)
        val framesPerSecond = 1_000 / 40

        repeat(framesPerSecond - 1) { phone.link.receive(babyUnit, audioFrame(it, amplitude = 30_000)) }
        runCurrent()
        assertFalse(phone.controller.crying)

        phone.link.receive(babyUnit, audioFrame(framesPerSecond, amplitude = 30_000))
        runCurrent()
        assertTrue(phone.controller.crying)
        assertEquals(1, phone.system.alerts)
    }

    @Test
    fun quietSoundNeverAlerts() = runTest {
        val phone = startedParent()
        phone.link.join(babyUnit)
        repeat(200) { phone.link.receive(babyUnit, audioFrame(it, amplitude = 50)) }
        runCurrent()

        assertFalse(phone.controller.crying)
        assertEquals(0, phone.system.alerts)
    }

    @Test
    fun ongoingCryingAlertsAgainOnlyEvery30Seconds() = runTest {
        val phone = startedParent()
        phone.link.join(babyUnit)
        var seq = 0
        repeat(200) { phone.link.receive(babyUnit, audioFrame(seq++, amplitude = 30_000)) }
        runCurrent()
        assertEquals(1, phone.system.alerts)

        advanceTimeBy(30_001)
        phone.link.receive(babyUnit, audioFrame(seq, amplitude = 30_000))
        runCurrent()
        assertEquals(2, phone.system.alerts)
    }

    @Test
    fun thresholdIsKept() = runTest {
        val phone = Phone(this)
        phone.controller.updateThreshold(0.3f)

        assertEquals(0.3f, Phone(this, phone.system).controller.threshold)
    }

    @Test
    fun parentUnitReconnectsTwoSecondsAfterALoss() = runTest {
        val phone = startedParent()
        phone.link.join(babyUnit)
        runCurrent()
        phone.link.fail("gone")
        runCurrent()
        assertEquals(1, phone.controller.drops)

        advanceTimeBy(1_999)
        assertEquals(1, phone.link.starts.size)
        advanceTimeBy(2)
        assertEquals(FakePeerLink.Start(Role.Join, false, someUnitId), phone.link.starts.last())
        assertEquals(2, phone.link.starts.size)
    }

    @Test
    fun stoppingCancelsAPendingReconnect() = runTest {
        val phone = startedParent()
        phone.link.fail("gone")
        runCurrent()
        phone.controller.stop()

        advanceTimeBy(5_000)
        assertEquals(1, phone.link.starts.size)
        assertFalse(phone.speaker.running)
        assertNull(phone.system.keptRunningAs)
    }

    // ---- Link test tools ----

    @Test
    fun throughputTestSendsToEveryPeerAndEndsWhenAllReport() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.join(parentB)
        phone.link.sendDelayMs = 10
        runCurrent()

        phone.controller.runBulkTest()
        assertTrue(phone.controller.bulkRunning)
        advanceTimeBy(10_100)
        assertEquals(1, phone.link.sentTo(parentA, Frame.BULK_END).size)
        assertEquals(1, phone.link.sentTo(parentB, Frame.BULK_END).size)

        phone.link.receive(parentA, Frame(Frame.BULK_RESULT, "1 kbps".encodeToByteArray()))
        runCurrent()
        assertTrue(phone.controller.bulkRunning, "still waiting for parent B")
        phone.link.receive(parentB, Frame(Frame.BULK_RESULT, "1 kbps".encodeToByteArray()))
        runCurrent()
        assertFalse(phone.controller.bulkRunning)
    }

    @Test
    fun throughputTestDoesntWaitForAPeerThatLeft() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        phone.link.sendDelayMs = 10
        runCurrent()
        phone.controller.runBulkTest()

        phone.link.leave(parentA)
        advanceTimeBy(100)
        assertFalse(phone.controller.bulkRunning)
    }

    @Test
    fun throughputReceiverReportsBackToTheSender() = runTest {
        val phone = startedParent()
        phone.link.join(babyUnit)
        repeat(3) { phone.link.receive(babyUnit, Frame(Frame.BULK, ByteArray(1_000))) }
        phone.link.receive(babyUnit, Frame(Frame.BULK_END, ByteArray(0)))
        runCurrent()

        val result = phone.link.sentTo(babyUnit, Frame.BULK_RESULT).single().payload.decodeToString()
        assertTrue("3000 bytes" in result, result)
    }

    @Test
    fun simulatedStreamGoesToAllPeersUntilStopped() = runTest {
        val phone = startedBaby()
        phone.link.join(parentA)
        runCurrent()

        phone.controller.toggleStream()
        advanceTimeBy(1_000)
        val packets = phone.link.broadcasts(Frame.STREAM)
        assertEquals(50, packets.size, "one every 20 ms")
        assertEquals((0 until 50).toList(), packets.map { it.payload.getInt(0) })

        phone.controller.toggleStream()
        advanceTimeBy(1_000)
        assertEquals(50, phone.link.broadcasts(Frame.STREAM).size)
    }

    // ---- Helpers ----

    private fun TestScope.startedBaby(): Phone = Phone(this).apply {
        controller.start(MonitorRole.Baby)
        runCurrent()
    }

    private fun TestScope.startedParent(): Phone = Phone(this).apply {
        controller.pairWith(PairingCode.format(someUnitId))
        controller.start(MonitorRole.Parent)
        runCurrent()
    }

    private fun tone(amplitude: Int): ShortArray =
        ShortArray(AUDIO_FRAME_SAMPLES) { (amplitude * sin(2 * PI * 440 * it / 8_000)).toInt().toShort() }

    private fun audioFrame(seq: Int, amplitude: Int): Frame =
        Frame(Frame.AUDIO, AudioPacket.encode(seq, tone(amplitude), AdpcmState()))
}

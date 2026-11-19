package com.link184.babycamera.security

import com.link184.babycamera.FakeSecretStore
import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.PairingCode
import com.link184.babycamera.link.Peer
import com.link184.babycamera.link.PeerEvent
import com.link184.babycamera.link.PeerLink
import com.link184.babycamera.link.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Two or more [SecureLink]s talking over [WireLink]s, like phones over the air. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureLinkTest {

    private class Phone(scope: TestScope, val role: Role, name: String) {
        val raw = WireLink(role)
        val pairings = Pairings(FakeSecretStore(), nowMillis = { scope.testScheduler.currentTime })
        val link = SecureLink(raw, pairings, testCrypto, scope.backgroundScope, deviceName = name)
        val events = mutableListOf<PeerEvent>()
        val logLines = mutableListOf<String>()

        init {
            scope.backgroundScope.launch { link.events.collect { events += it } }
            scope.backgroundScope.launch { link.log.collect { logLines += it } }
        }

        val joined get() = events.filterIsInstance<PeerEvent.Joined>().map { it.peer }
        val left get() = events.filterIsInstance<PeerEvent.Left>().map { it.peer }
        val received get() = events.filterIsInstance<PeerEvent.Data>().map { it.bytes.decodeToString() }
    }

    // Raw peers as each side's Bluetooth link names them.
    private val parentOnBaby = Peer(1, "AA:BB:CC:DD:EE:01")
    private val otherParentOnBaby = Peer(2, "AA:BB:CC:DD:EE:02")
    private val strangerOnBaby = Peer(3, "AA:BB:CC:DD:EE:03")
    private val babyOnParent = Peer(10, "baby unit")

    private fun TestScope.baby(): Phone = Phone(this, Role.Host, "Baby phone").apply {
        link.start(Role.Host, false, pairings.ownUnitId)
    }

    private fun TestScope.parent(code: String, name: String = "Pixel 8a"): Phone = Phone(this, Role.Join, name).apply {
        assertTrue(pairings.pairWith(code))
        link.start(Role.Join, false, pairings.babyUnit.value!!.unitId)
    }

    private fun TestScope.connect(baby: Phone, parent: Phone, parentPeer: Peer = parentOnBaby) {
        WireLink.connect(baby.raw, parentPeer, parent.raw, babyOnParent)
        runCurrent()
    }

    private fun TestScope.paired(): Pair<Phone, Phone> {
        val baby = baby()
        val parent = parent(baby.pairings.newParentCode())
        connect(baby, parent)
        return baby to parent
    }

    @Test
    fun aPairedParentConnectsAndEverythingIsEncrypted() = runTest {
        val (baby, parent) = paired()

        assertEquals(listOf(Peer(parentOnBaby.id, "Pixel 8a")), baby.joined, "named by the handshake, not by Bluetooth")
        assertEquals(listOf(babyOnParent), parent.joined)
        assertIs<LinkState.Connected>(baby.link.state.value)
        assertIs<LinkState.Connected>(parent.link.state.value)
        assertTrue(baby.pairings.parents.value.single().confirmed)
        assertEquals("Pixel 8a", baby.pairings.parents.value.single().name)

        parent.link.send(babyOnParent, "a secret ping".encodeToByteArray())
        assertTrue(baby.link.offer(baby.joined.single(), "the baby's sound".encodeToByteArray()))
        runCurrent()

        assertEquals(listOf("a secret ping"), baby.received)
        assertEquals(listOf("the baby's sound"), parent.received)
        val onTheAir = parent.raw.transmitted.map { it.second } + baby.raw.transmitted.map { it.second }
        assertFalse(onTheAir.any { it.containsText("secret") || it.containsText("sound") || it.containsText("Pixel") })
    }

    @Test
    fun aPhoneWithTheWrongKeyIsDroppedAndToldToRescan() = runTest {
        val baby = baby()
        baby.pairings.newParentCode()
        val parent = parent(PairingCode.format(baby.pairings.ownUnitId, ByteArray(32) { 5 }))
        connect(baby, parent)

        assertEquals(listOf(parentOnBaby), baby.raw.disconnects)
        assertTrue(baby.events.isEmpty())
        assertIs<LinkState.Working>(baby.link.state.value)
        val failed = assertIs<LinkState.Failed>(parent.link.state.value)
        assertTrue("QR code again" in failed.reason, failed.reason)
        assertTrue(parent.joined.isEmpty())
    }

    @Test
    fun aParentNeedsAScannedCodeToStart() = runTest {
        val parent = Phone(this, Role.Join, "Pixel 8a")
        parent.link.start(Role.Join, false, "0f8fad5b-d9cb-469f-a165-70867728950e")
        assertIs<LinkState.Failed>(parent.link.state.value)
        assertEquals(0, parent.raw.starts)
    }

    @Test
    fun anUnusedCodeExpires() = runTest {
        val baby = baby()
        val code = baby.pairings.newParentCode()
        advanceTimeBy(Pairings.PENDING_MS)
        val parent = parent(code)
        connect(baby, parent)

        assertEquals(listOf(parentOnBaby), baby.raw.disconnects)
        assertTrue(baby.pairings.parents.value.isEmpty())
    }

    @Test
    fun aConfirmedParentKeepsWorkingLaterAndReconnects() = runTest {
        val (baby, parent) = paired()
        parent.raw.disconnect(babyOnParent)
        runCurrent()
        advanceTimeBy(Pairings.PENDING_MS * 10)

        connect(baby, parent)
        assertEquals(2, baby.joined.size)
        assertIs<LinkState.Connected>(parent.link.state.value)
    }

    @Test
    fun removingAParentDisconnectsItAndKeepsItOut() = runTest {
        val baby = baby()
        val first = parent(baby.pairings.newParentCode(), name = "First")
        val second = parent(baby.pairings.newParentCode(), name = "Second")
        connect(baby, first, parentOnBaby)
        WireLink.connect(baby.raw, otherParentOnBaby, second.raw, babyOnParent)
        runCurrent()
        assertEquals(listOf("First", "Second"), baby.joined.map { it.name })

        baby.pairings.removeParent(baby.pairings.parents.value.first { it.name == "First" }.id)
        runCurrent()

        assertEquals(listOf("First"), baby.left.map { it.name })
        assertIs<LinkState.Failed>(first.link.state.value)
        assertIs<LinkState.Connected>(second.link.state.value, "the other parent unit isn't affected")

        connect(baby, first)
        assertEquals(2, baby.joined.size, "its key doesn't work any more")
    }

    @Test
    fun aReplayedHandshakeGetsNowhere() = runTest {
        val (baby, parent) = paired()
        val handshake = parent.raw.transmitted.first().second

        // Someone recorded it over the air and plays it back on their own connection.
        val stranger = WireLink(Role.Join)
        WireLink.connect(baby.raw, strangerOnBaby, stranger, babyOnParent)
        runCurrent()
        stranger.send(babyOnParent, handshake)
        runCurrent()
        assertTrue(strangerOnBaby !in baby.raw.disconnects, "the baby unit answers; only the key holder can go on")

        advanceTimeBy(SecureLink.HANDSHAKE_TIMEOUT_MS + 1)
        assertEquals(listOf(strangerOnBaby), baby.raw.disconnects)
        assertEquals(1, baby.joined.size)
        assertIs<LinkState.Connected>(baby.link.state.value, "the real parent unit is still connected")
    }

    @Test
    fun aConnectionThatSaysNothingIsDroppedAfterTheTimeout() = runTest {
        val baby = baby()
        WireLink.connect(baby.raw, strangerOnBaby, WireLink(Role.Join), babyOnParent)
        runCurrent()

        advanceTimeBy(SecureLink.HANDSHAKE_TIMEOUT_MS - 1)
        assertTrue(baby.raw.disconnects.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(strangerOnBaby), baby.raw.disconnects)
    }

    @Test
    fun plainFramesAreNeverPassedOn() = runTest {
        val baby = baby()
        baby.pairings.newParentCode()
        val stranger = WireLink(Role.Join)
        WireLink.connect(baby.raw, strangerOnBaby, stranger, babyOnParent)
        runCurrent()

        stranger.send(babyOnParent, Frame(Frame.PING, ByteArray(8)).encode())
        runCurrent()

        assertEquals(listOf(strangerOnBaby), baby.raw.disconnects)
        assertTrue(baby.events.isEmpty())
        assertFalse(baby.link.offer(Peer(strangerOnBaby.id, "x"), "sound".encodeToByteArray()), "nothing goes out to it either")
    }

    @Test
    fun replayedMessagesAreIgnored() = runTest {
        val (baby, parent) = paired()
        parent.link.send(babyOnParent, "once".encodeToByteArray())
        runCurrent()
        val recorded = parent.raw.transmitted.last().second

        baby.raw.inject(parentOnBaby, recorded)
        runCurrent()

        assertEquals(listOf("once"), baby.received)
        assertIs<LinkState.Connected>(baby.link.state.value)
    }

    @Test
    fun aChangedMessageDropsTheConnection() = runTest {
        val (baby, parent) = paired()
        parent.raw.tamper = { bytes -> bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } }
        parent.link.send(babyOnParent, "turn the volume down".encodeToByteArray())
        runCurrent()

        assertTrue(baby.received.isEmpty())
        assertEquals(listOf(parentOnBaby), baby.raw.disconnects)
        assertEquals(1, baby.left.size)
    }

    @Test
    fun lostMessagesDontBreakTheConnection() = runTest {
        val (baby, parent) = paired()
        val peer = baby.joined.single()
        baby.link.offer(peer, "1".encodeToByteArray())
        baby.raw.tamper = { null } // lost on the air
        baby.link.offer(peer, "2".encodeToByteArray())
        baby.raw.tamper = null
        baby.link.offer(peer, "3".encodeToByteArray())
        runCurrent()

        assertEquals(listOf("1", "3"), parent.received)
        assertIs<LinkState.Connected>(parent.link.state.value)
    }

    private fun ByteArray.containsText(text: String): Boolean {
        val needle = text.encodeToByteArray()
        return (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
    }
}

/**
 * A raw [PeerLink] wired straight to others with [connect]: what one side sends arrives on the
 * other, unencrypted, like Bluetooth. Records everything sent, and can change or lose it.
 */
private class WireLink(private val role: Role) : PeerLink {
    private class Route(val local: Peer, val other: WireLink, val asSeenThere: Peer)

    override val state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val log: Flow<String> = MutableSharedFlow()
    override val rssi = MutableStateFlow<Int?>(null)
    override val supportsLongRange = false
    private val eventChannel = Channel<PeerEvent>(Channel.UNLIMITED)
    override val events: Flow<PeerEvent> = eventChannel.receiveAsFlow()

    private val routes = LinkedHashMap<Int, Route>()
    var starts = 0
    val disconnects = mutableListOf<Peer>()

    /** What this side put on the air, and to whom. */
    val transmitted = mutableListOf<Pair<Peer, ByteArray>>()

    /** Changes what goes out; returning null loses it. */
    var tamper: ((ByteArray) -> ByteArray?)? = null

    override fun start(role: Role, longRange: Boolean, unitId: String) {
        starts++
        state.value = LinkState.Working("Starting")
    }

    override suspend fun send(peer: Peer, bytes: ByteArray) {
        transmit(peer, bytes)
    }

    override fun offer(peer: Peer, bytes: ByteArray): Boolean = transmit(peer, bytes)

    private fun transmit(peer: Peer, bytes: ByteArray): Boolean {
        val route = routes[peer.id] ?: return false
        transmitted += peer to bytes
        val out = tamper.let { if (it == null) bytes else it(bytes) ?: return true }
        route.other.eventChannel.trySend(PeerEvent.Data(route.asSeenThere, out))
        return true
    }

    /** Bytes arriving as if [from] sent them. */
    fun inject(from: Peer, bytes: ByteArray) {
        eventChannel.trySend(PeerEvent.Data(from, bytes))
    }

    override fun disconnect(peer: Peer) {
        disconnects += peer
        val route = routes[peer.id] ?: return
        lost(peer)
        route.other.lost(route.asSeenThere)
    }

    override fun stop() {
        routes.values.toList().forEach { disconnect(it.local) }
        state.value = LinkState.Idle
    }

    private fun joined(route: Route) {
        routes[route.local.id] = route
        eventChannel.trySend(PeerEvent.Joined(route.local))
        state.value = LinkState.Connected(routes.values.map { it.local })
    }

    private fun lost(peer: Peer) {
        routes.remove(peer.id) ?: return
        eventChannel.trySend(PeerEvent.Left(peer))
        state.value = when {
            routes.isNotEmpty() -> LinkState.Connected(routes.values.map { it.local })
            role == Role.Host -> LinkState.Working("Waiting")
            else -> LinkState.Failed("Connection lost")
        }
    }

    companion object {
        /** Connects [host], which calls the other side [hostSees], to [joiner], which calls the host [joinerSees]. */
        fun connect(host: WireLink, hostSees: Peer, joiner: WireLink, joinerSees: Peer) {
            host.joined(Route(hostSees, joiner, joinerSees))
            joiner.joined(Route(joinerSees, host, hostSees))
        }
    }
}

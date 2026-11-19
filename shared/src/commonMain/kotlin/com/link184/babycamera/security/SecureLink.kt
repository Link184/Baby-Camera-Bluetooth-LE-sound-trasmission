package com.link184.babycamera.security

import com.link184.babycamera.link.Frame
import com.link184.babycamera.link.FrameDecoder
import com.link184.babycamera.link.LinkState
import com.link184.babycamera.link.Peer
import com.link184.babycamera.link.PeerEvent
import com.link184.babycamera.link.PeerLink
import com.link184.babycamera.link.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Encrypts a [PeerLink]. Bluetooth itself carries everything unencrypted and unauthenticated, so
 * every connection starts with a Noise handshake (see [NoiseHandshake]) using the key from the
 * baby unit's QR code:
 *
 * 1. The parent unit sends handshake message 1, proving it knows a key the baby unit gave out.
 *    The baby unit tries each of its parents' keys ([Pairings.acceptedParents]); none fits: dropped.
 * 2. The baby unit answers with message 2, proving it knows the key too.
 * 3. The parent unit sends its first encrypted message. Only then does the baby unit count it as
 *    connected, so replaying an old message 1 gets an attacker nowhere.
 *
 * Above this link a peer appears ([PeerEvent.Joined]) only after that, and everything sent to it
 * is encrypted and authenticated. A connection that doesn't finish the handshake within
 * [HANDSHAKE_TIMEOUT_MS], or sends anything forged, is dropped. Removing a parent unit from
 * [Pairings] drops its connection. Main thread only.
 */
class SecureLink(
    private val raw: PeerLink,
    private val pairings: Pairings,
    private val crypto: NoiseCrypto,
    private val scope: CoroutineScope,
    /** Sent to the baby unit, encrypted, so its list of parent units can say which phone is which. */
    private val deviceName: String,
) : PeerLink {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    private val ownLog = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val eventChannel = Channel<PeerEvent>(Channel.UNLIMITED)

    override val state: StateFlow<LinkState> = _state
    override val log: Flow<String> = merge(raw.log, ownLog)
    override val events: Flow<PeerEvent> = eventChannel.receiveAsFlow()
    override val rssi: StateFlow<Int?> = raw.rssi
    override val supportsLongRange: Boolean get() = raw.supportsLongRange

    private var role = Role.Join
    private var unitId = ""
    private var rawState: LinkState = LinkState.Idle

    /** Why a joiner's last connection ended, when it's about the keys; shown instead of the raw reason. */
    private var keyProblem: String? = null
    private val sessions = LinkedHashMap<Int, Session>()

    private class Session(val raw: Peer) {
        val decoder = FrameDecoder()
        var handshake: NoiseHandshake? = null
        var transport: NoiseTransport? = null
        var authenticated = false
        var name = raw.name
        var parentId: String? = null
        var timeout: Job? = null
        val peer get() = Peer(raw.id, name)
    }

    init {
        scope.launch {
            raw.state.collect {
                rawState = it
                publish()
            }
        }
        scope.launch { raw.events.collect(::onRawEvent) }
        scope.launch {
            pairings.parents.collect { parents ->
                val ids = parents.map { it.id }.toSet()
                sessions.values.filter { it.parentId != null && it.parentId !in ids }.forEach {
                    log("${it.name} was removed - disconnecting it")
                    raw.disconnect(it.raw)
                }
            }
        }
    }

    override fun start(role: Role, longRange: Boolean, unitId: String) {
        this.role = role
        this.unitId = unitId
        keyProblem = null
        if (role == Role.Join && pairings.babyUnit.value == null) {
            _state.value = LinkState.Failed("Scan the baby unit's QR code first")
            return
        }
        raw.start(role, longRange, unitId)
    }

    override suspend fun send(peer: Peer, bytes: ByteArray) {
        val session = sessions[peer.id]?.takeIf { it.authenticated } ?: return
        raw.send(session.raw, sealed(session, bytes))
    }

    override fun offer(peer: Peer, bytes: ByteArray): Boolean {
        val session = sessions[peer.id]?.takeIf { it.authenticated } ?: return false
        return raw.offer(session.raw, sealed(session, bytes))
    }

    override fun disconnect(peer: Peer) {
        sessions[peer.id]?.let { raw.disconnect(it.raw) }
    }

    override fun stop() {
        raw.stop()
    }

    private fun sealed(session: Session, bytes: ByteArray): ByteArray =
        Frame(SEALED, checkNotNull(session.transport).seal(bytes)).encode()

    private suspend fun onRawEvent(event: PeerEvent) {
        when (event) {
            is PeerEvent.Joined -> {
                val session = Session(event.peer)
                sessions[event.peer.id] = session
                session.timeout = scope.launch {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    if (!session.authenticated && sessions[event.peer.id] === session) {
                        log("${session.name} didn't finish the key check in time - dropping it")
                        raw.disconnect(session.raw)
                    }
                }
                if (role == Role.Join) startHandshake(session)
            }

            is PeerEvent.Data -> {
                val session = sessions[event.peer.id] ?: return
                val frames = try {
                    session.decoder.feed(event.bytes)
                } catch (e: IllegalArgumentException) {
                    drop(session, "garbled data")
                    return
                }
                for (frame in frames) {
                    if (!handle(session, frame)) break
                }
            }

            is PeerEvent.Left -> {
                val session = sessions.remove(event.peer.id) ?: return
                session.timeout?.cancel()
                if (session.authenticated) {
                    eventChannel.trySend(PeerEvent.Left(session.peer))
                } else if (role == Role.Join && session.handshake != null) {
                    keyProblem = "The baby unit ended the connection during the key check. If this keeps happening, " +
                        "scan its QR code again: it may have removed this phone."
                    log(keyProblem!!)
                }
                publish()
            }
        }
    }

    /** False when the connection was dropped, so the rest of its data is ignored. */
    private suspend fun handle(session: Session, frame: Frame): Boolean = when (frame.type) {
        HANDSHAKE_1 -> if (role == Role.Host && session.transport == null) acceptParent(session, frame.payload) else drop(session, "unexpected handshake")
        HANDSHAKE_2 -> if (role == Role.Join && session.handshake != null) finishHandshake(session, frame.payload) else drop(session, "unexpected handshake")
        SEALED -> openSealed(session, frame.payload)
        else -> drop(session, "unknown message type ${frame.type}")
    }

    // ---- Parent unit (initiator) ----

    private suspend fun startHandshake(session: Session) {
        val key = pairings.babyUnit.value?.key ?: run {
            drop(session, "no baby unit key")
            return
        }
        val handshake = NoiseHandshake.initiator(crypto, key, prologue())
        session.handshake = handshake
        log("Checking keys with the baby unit")
        raw.send(session.raw, Frame(HANDSHAKE_1, handshake.writeFirst(deviceName.encodeToByteArray())).encode())
    }

    private suspend fun finishHandshake(session: Session, message: ByteArray): Boolean {
        val handshake = checkNotNull(session.handshake)
        handshake.readSecond(message) ?: return drop(session, "the baby unit failed the key check")
        val transport = handshake.split()
        session.transport = transport
        session.handshake = null
        // The first encrypted message tells the baby unit that this phone holds the key.
        raw.send(session.raw, Frame(SEALED, transport.seal(ByteArray(0))).encode())
        authenticate(session)
        return true
    }

    // ---- Baby unit (responder) ----

    private suspend fun acceptParent(session: Session, message: ByteArray): Boolean {
        for (parent in pairings.acceptedParents()) {
            val handshake = NoiseHandshake.responder(crypto, parent.key, prologue())
            val payload = handshake.readFirst(message) ?: continue
            val reply = handshake.writeSecond(ByteArray(0)) ?: return drop(session, "unusable key")
            session.name = payload.decodeToString().filter { !it.isISOControl() }.take(MAX_NAME_LENGTH).ifBlank { "Parent unit" }
            session.parentId = parent.id
            session.transport = handshake.split()
            raw.send(session.raw, Frame(HANDSHAKE_2, reply).encode())
            return true
        }
        return drop(session, "not a paired parent unit")
    }

    // ---- Both ----

    private fun openSealed(session: Session, message: ByteArray): Boolean {
        val transport = session.transport ?: return drop(session, "encrypted data before the key check")
        when (val opened = transport.open(message)) {
            NoiseTransport.Opened.Forged -> return drop(session, "a message failed its authenticity check")
            NoiseTransport.Opened.Replayed -> return true
            is NoiseTransport.Opened.Message -> {
                if (!session.authenticated) {
                    session.parentId?.let { pairings.confirmParent(it, session.name) }
                    authenticate(session)
                }
                if (opened.plaintext.isNotEmpty()) eventChannel.trySend(PeerEvent.Data(session.peer, opened.plaintext))
                return true
            }
        }
    }

    private fun authenticate(session: Session) {
        session.authenticated = true
        session.timeout?.cancel()
        keyProblem = null
        log("${session.name}: key check passed, connection encrypted")
        eventChannel.trySend(PeerEvent.Joined(session.peer))
        publish()
    }

    private fun drop(session: Session, reason: String): Boolean {
        log("Dropping ${session.name}: $reason")
        raw.disconnect(session.raw)
        return false
    }

    /** Ties the handshake to this baby unit, so a key can't be used against another one. */
    private fun prologue(): ByteArray = "BabyCamera 1 $unitId".encodeToByteArray()

    /** Above this link, only peers that passed the key check count as connected. */
    private fun publish() {
        val ready = sessions.values.filter { it.authenticated }.map { it.peer }
        _state.value = when (val state = rawState) {
            is LinkState.Connected -> when {
                ready.isNotEmpty() -> LinkState.Connected(ready)
                role == Role.Host -> LinkState.Working("Waiting for a paired parent unit")
                else -> LinkState.Working("Checking keys with the baby unit")
            }
            is LinkState.Failed -> keyProblem?.let { LinkState.Failed(it) } ?: state
            else -> state
        }
    }

    private fun log(message: String) {
        ownLog.tryEmit(message)
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val MAX_NAME_LENGTH = 40

        // Frame types on the raw link; the controller's own frames travel inside SEALED.
        private const val HANDSHAKE_1: Byte = 0x51
        private const val HANDSHAKE_2: Byte = 0x52
        private const val SEALED: Byte = 0x53
    }
}

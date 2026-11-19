package com.link184.babycamera.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseTest {
    private val crypto = testCrypto

    // The Noise_NNpsk0_25519_ChaChaPoly_SHA256 vector from cacophony, the reference test vectors
    // (github.com/haskell-cryptography/cacophony, vectors/cacophony.txt).
    private val prologue = hex("4a6f686e2047616c74")
    private val psk = hex("54686973206973206d7920417573747269616e20706572737065637469766521")
    private val initiatorEphemeral = hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
    private val responderEphemeral = hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")

    // Fresh ephemeral keys, as in the app. Only the vector test replays fixed ones: encrypting twice
    // with the same key and nonce is refused (BouncyCastle checks), and must never happen anyway.
    private fun initiator(psk: ByteArray = this.psk) = NoiseHandshake.initiator(crypto, psk, prologue)

    private fun responder(psk: ByteArray = this.psk) = NoiseHandshake.responder(crypto, psk, prologue)

    @Test
    fun matchesTheReferenceVectors() {
        val initiator = NoiseHandshake.initiator(crypto, psk, prologue) { crypto.keyPairFromPrivate(initiatorEphemeral) }
        val responder = NoiseHandshake.responder(crypto, psk, prologue) { crypto.keyPairFromPrivate(responderEphemeral) }

        val first = initiator.writeFirst(hex("4c756477696720766f6e204d69736573"))
        assertContentEquals(
            hex("ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c794479b962b8aff8485742ac32f905ba45369e2465fb59e138a93d67a0d1266b6a54"),
            first,
        )
        assertContentEquals(hex("4c756477696720766f6e204d69736573"), responder.readFirst(first))

        val second = assertNotNull(responder.writeSecond(hex("4d757272617920526f746862617264")))
        assertContentEquals(
            hex("95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843d6062704d5a9c422a8e834423f8c1feada7e8d0d910a1a2cd030fb584221e3"),
            second,
        )
        assertContentEquals(hex("4d757272617920526f746862617264"), initiator.readSecond(second))

        val handshakeHash = hex("f4d03dc34495c95729ea6de9e1b59004b59733102488b3e24bc441e0be208eaf")
        assertContentEquals(handshakeHash, initiator.handshakeHash)
        assertContentEquals(handshakeHash, responder.handshakeHash)

        // The vectors' transport messages use implicit nonces 0, 1, ...: each side's first is nonce 0.
        val initiatorKeys = initiator.split()
        val responderKeys = responder.split()
        assertContentEquals(
            hex("e632c3763d7669067383433197a3baddf146e9e70ad4b4e9e59e0f"),
            initiatorKeys.send.encryptAt(0, hex("462e20412e20486179656b")),
        )
        assertContentEquals(
            hex("64c6bee32ea91c8474bb4c21d7a700109ad45af77b29764ba5eb1e"),
            responderKeys.send.encryptAt(0, hex("4361726c204d656e676572")),
        )
    }

    @Test
    fun wrongKeyFailsOnBothSides() {
        val otherPsk = ByteArray(32) { 1 }
        // A parent with the wrong key: the baby unit can't read its first message.
        assertNull(responder(otherPsk).readFirst(initiator().writeFirst(ByteArray(0))))

        // A baby unit's answer that was changed, or made with another key, is refused by the parent.
        val parent = initiator()
        val baby = responder()
        assertNotNull(baby.readFirst(parent.writeFirst(ByteArray(0))))
        val reply = assertNotNull(baby.writeSecond(ByteArray(0)))
        reply[reply.size - 1] = (reply[reply.size - 1].toInt() xor 1).toByte()
        assertNull(parent.readSecond(reply))
    }

    @Test
    fun differentPrologueFails() {
        val first = initiator().writeFirst(ByteArray(0))
        val otherUnit = NoiseHandshake.responder(crypto, psk, hex("00"))
        assertNull(otherUnit.readFirst(first), "a key for one baby unit doesn't work against another")
    }

    @Test
    fun tamperedMessagesAreRejected() {
        val first = initiator().writeFirst(hex("01020304"))
        for (i in first.indices) {
            val changed = first.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(responder().readFirst(changed), "byte $i changed")
        }
        assertNull(responder().readFirst(first.copyOf(40)), "cut short")
    }

    @Test
    fun freshKeysEachTime() {
        val a = initiator().writeFirst(ByteArray(0))
        val b = initiator().writeFirst(ByteArray(0))
        assertTrue(!a.contentEquals(b))
    }

    // ---- Transport ----

    private fun connectedPair(): Pair<NoiseTransport, NoiseTransport> {
        val initiator = initiator()
        val responder = responder()
        assertNotNull(responder.readFirst(initiator.writeFirst(ByteArray(0))))
        assertNotNull(initiator.readSecond(assertNotNull(responder.writeSecond(ByteArray(0)))))
        return initiator.split() to responder.split()
    }

    private fun NoiseTransport.openText(message: ByteArray): String? =
        (open(message) as? NoiseTransport.Opened.Message)?.plaintext?.decodeToString()

    @Test
    fun transportWorksBothWays() {
        val (parent, baby) = connectedPair()
        assertEquals("hello", baby.openText(parent.seal("hello".encodeToByteArray())))
        assertEquals("hi", parent.openText(baby.seal("hi".encodeToByteArray())))
        assertEquals(5 + NoiseTransport.OVERHEAD, parent.seal("hello".encodeToByteArray()).size)
    }

    @Test
    fun gapsAndSlightReorderingAreFineButReplaysAreNot() {
        val (parent, baby) = connectedPair()
        val messages = (0 until 5).map { parent.seal("m$it".encodeToByteArray()) }

        assertEquals("m0", baby.openText(messages[0]))
        assertEquals("m3", baby.openText(messages[3]), "m1 and m2 were skipped")
        assertEquals("m2", baby.openText(messages[2]), "arrived late, but not seen before")
        assertIs<NoiseTransport.Opened.Replayed>(baby.open(messages[2]))
        assertIs<NoiseTransport.Opened.Replayed>(baby.open(messages[0]))
        assertIs<NoiseTransport.Opened.Replayed>(baby.open(messages[3]))
        assertEquals("m4", baby.openText(messages[4]))
        assertEquals("m1", baby.openText(messages[1]))
    }

    @Test
    fun messagesOlderThanTheWindowAreRefused() {
        val (parent, baby) = connectedPair()
        val old = parent.seal("old".encodeToByteArray())
        repeat(NoiseTransport.REPLAY_WINDOW) { parent.seal(ByteArray(0)) }
        assertEquals("new", baby.openText(parent.seal("new".encodeToByteArray())))
        assertIs<NoiseTransport.Opened.Replayed>(baby.open(old))
    }

    @Test
    fun forgedTransportMessagesAreCaught() {
        val (parent, baby) = connectedPair()
        val message = parent.seal("hello".encodeToByteArray())
        val changed = message.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertIs<NoiseTransport.Opened.Forged>(baby.open(changed))
        assertIs<NoiseTransport.Opened.Forged>(baby.open(message.copyOf(10)))
        assertEquals("hello", baby.openText(message), "a forgery doesn't use up the real message's nonce")

        // Its own messages coming back are no good either: each direction has its own key.
        assertIs<NoiseTransport.Opened.Forged>(parent.open(parent.seal("echo".encodeToByteArray())))
    }
}

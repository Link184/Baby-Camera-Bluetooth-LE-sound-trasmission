package com.link184.babycamera.security

import com.link184.babycamera.link.getLong
import com.link184.babycamera.link.putLong
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher

/*
 * The Noise Protocol Framework (noiseprotocol.org/noise.html, revision 34), only the parts
 * BabyCamera uses: the handshake pattern NNpsk0 with X25519, ChaCha20-Poly1305 and SHA-256.
 *
 *   -> psk, e        the parent unit proves it knows the pre-shared key (from the QR code)
 *   <- e, ee         the baby unit proves the same; both now share keys only they can know
 *
 * The ephemeral X25519 keys make every connection's keys new, so a recording stays unreadable
 * even if the pre-shared key leaks later. The primitives come from cryptography-kotlin; this file
 * only follows the spec. NoiseTest checks it against the published test vectors.
 */

/** X25519, ChaCha20-Poly1305, SHA-256 and HMAC from one cryptography provider. */
class NoiseCrypto(provider: CryptographyProvider) {
    private val xdh = provider.get(XDH)
    private val chaCha = provider.get(ChaCha20Poly1305)
    private val sha256 = provider.get(SHA256).hasher()
    private val hmac = provider.get(HMAC)

    fun generateKeyPair(): NoiseKeyPair {
        val pair = xdh.keyPairGenerator(XDH.Curve.X25519).generateKeyBlocking()
        return NoiseKeyPair(pair.privateKey, pair.publicKey.encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW))
    }

    /** For tests that replay fixed keys. */
    internal fun keyPairFromPrivate(raw: ByteArray): NoiseKeyPair {
        val private = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, raw)
        return NoiseKeyPair(private, private.getPublicKeyBlocking().encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW))
    }

    /** Null for a public key that can't be used, such as a low-order point. */
    internal fun dh(own: NoiseKeyPair, otherPublic: ByteArray): ByteArray? = try {
        val other = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, otherPublic)
        own.private.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(other)
    } catch (e: Exception) {
        null
    }

    internal fun hash(data: ByteArray): ByteArray = sha256.hashBlocking(data)

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator().generateSignatureBlocking(data)

    /** The spec's HKDF: two or three 32-byte outputs. */
    internal fun hkdf(chainingKey: ByteArray, input: ByteArray, outputs: Int): List<ByteArray> {
        val tempKey = hmac(chainingKey, input)
        val result = ArrayList<ByteArray>(outputs)
        var previous = ByteArray(0)
        for (i in 1..outputs) {
            previous = hmac(tempKey, previous + i.toByte())
            result += previous
        }
        return result
    }

    internal fun cipher(key: ByteArray): IvAuthenticatedCipher =
        chaCha.keyDecoder().decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key).cipher()
}

class NoiseKeyPair(internal val private: XDH.PrivateKey, val public: ByteArray)

/**
 * The spec's CipherState: a key and the nonce of the next message. Choosing IVs ourselves is what
 * the library calls delicate: here the spec does it, with a counter that never repeats per key.
 */
@OptIn(DelicateCryptographyApi::class)
class CipherState internal constructor(crypto: NoiseCrypto, key: ByteArray?) {
    private val cipher = key?.let(crypto::cipher)
    private var nonce = 0L

    /** Encrypts with the next nonce; before a key exists the spec sends plaintext. */
    internal fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = cipher ?: return plaintext
        return cipher.encryptWithIvBlocking(nonceBytes(nonce), plaintext, ad).also { nonce++ }
    }

    internal fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray? {
        val cipher = cipher ?: return ciphertext
        return open(cipher, nonce, ad, ciphertext)?.also { nonce++ }
    }

    /** Encrypts with an explicit [nonce], which the sender then sends along. */
    internal fun encryptAt(nonce: Long, plaintext: ByteArray): ByteArray =
        checkNotNull(cipher).encryptWithIvBlocking(nonceBytes(nonce), plaintext, EMPTY)

    internal fun decryptAt(nonce: Long, ciphertext: ByteArray): ByteArray? = open(checkNotNull(cipher), nonce, EMPTY, ciphertext)

    private fun open(cipher: IvAuthenticatedCipher, nonce: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < TAG_SIZE) return null
        return try {
            cipher.decryptWithIvBlocking(nonceBytes(nonce), ciphertext, ad)
        } catch (e: Exception) {
            null // wrong key, or changed on the way
        }
    }

    /** ChaChaPoly nonces: 32 zero bits, then the counter as 64-bit little-endian. */
    private fun nonceBytes(n: Long): ByteArray = ByteArray(12).also { out ->
        for (i in 0 until 8) out[4 + i] = (n ushr (8 * i)).toByte()
    }
}

/** The spec's SymmetricState. */
private class SymmetricState(private val crypto: NoiseCrypto, protocolName: String) {
    var h: ByteArray
    private var ck: ByteArray
    private var cipher = CipherState(crypto, null)

    init {
        val name = protocolName.encodeToByteArray()
        h = if (name.size <= HASH_SIZE) name.copyOf(HASH_SIZE) else crypto.hash(name)
        ck = h
    }

    fun mixHash(data: ByteArray) {
        h = crypto.hash(h + data)
    }

    fun mixKey(input: ByteArray) {
        val (newCk, key) = crypto.hkdf(ck, input, 2)
        ck = newCk
        cipher = CipherState(crypto, key)
    }

    fun mixKeyAndHash(input: ByteArray) {
        val (newCk, tempH, key) = crypto.hkdf(ck, input, 3)
        ck = newCk
        mixHash(tempH)
        cipher = CipherState(crypto, key)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encryptWithAd(h, plaintext).also(::mixHash)

    fun decryptAndHash(ciphertext: ByteArray): ByteArray? = cipher.decryptWithAd(h, ciphertext)?.also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = crypto.hkdf(ck, EMPTY, 2)
        return CipherState(crypto, first) to CipherState(crypto, second)
    }
}

/**
 * One NNpsk0 handshake. The parent unit is the initiator: [writeFirst], then [readSecond]. The
 * baby unit is the responder: [readFirst], then [writeSecond]. Then [split] gives the keys.
 * A failed read returns null, after which the object must be thrown away.
 */
class NoiseHandshake private constructor(
    private val crypto: NoiseCrypto,
    private val initiator: Boolean,
    private val psk: ByteArray,
    prologue: ByteArray,
    private val newEphemeral: () -> NoiseKeyPair,
) {
    private val state = SymmetricState(crypto, PROTOCOL_NAME)
    private var ephemeral: NoiseKeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var step = 0

    init {
        require(psk.size == PSK_SIZE) { "The pre-shared key must be $PSK_SIZE bytes" }
        state.mixHash(prologue)
    }

    /** Both sides end with the same value; it identifies this handshake. */
    val handshakeHash: ByteArray get() = state.h

    fun writeFirst(payload: ByteArray): ByteArray {
        check(initiator && step == 0)
        state.mixKeyAndHash(psk)
        val e = newEphemeral().also { ephemeral = it }
        state.mixHash(e.public)
        state.mixKey(e.public)
        step = 1
        return e.public + state.encryptAndHash(payload)
    }

    /** Returns the payload, or null if the sender doesn't know the pre-shared key. */
    fun readFirst(message: ByteArray): ByteArray? {
        check(!initiator && step == 0)
        if (message.size < KEY_SIZE + TAG_SIZE) return null
        state.mixKeyAndHash(psk)
        val re = message.copyOfRange(0, KEY_SIZE)
        state.mixHash(re)
        state.mixKey(re)
        val payload = state.decryptAndHash(message.copyOfRange(KEY_SIZE, message.size)) ?: return null
        remoteEphemeral = re
        step = 1
        return payload
    }

    /** Null if the parent unit's ephemeral key is unusable. */
    fun writeSecond(payload: ByteArray): ByteArray? {
        check(!initiator && step == 1)
        val e = newEphemeral().also { ephemeral = it }
        state.mixHash(e.public)
        state.mixKey(e.public)
        state.mixKey(crypto.dh(e, checkNotNull(remoteEphemeral)) ?: return null)
        step = 2
        return e.public + state.encryptAndHash(payload)
    }

    /** Returns the payload, or null if the baby unit doesn't know the pre-shared key. */
    fun readSecond(message: ByteArray): ByteArray? {
        check(initiator && step == 1)
        if (message.size < KEY_SIZE + TAG_SIZE) return null
        val re = message.copyOfRange(0, KEY_SIZE)
        state.mixHash(re)
        state.mixKey(re)
        state.mixKey(crypto.dh(checkNotNull(ephemeral), re) ?: return null)
        val payload = state.decryptAndHash(message.copyOfRange(KEY_SIZE, message.size)) ?: return null
        step = 2
        return payload
    }

    fun split(): NoiseTransport {
        check(step == 2) { "Handshake not finished" }
        val (initiatorToResponder, responderToInitiator) = state.split()
        return if (initiator) {
            NoiseTransport(send = initiatorToResponder, receive = responderToInitiator)
        } else {
            NoiseTransport(send = responderToInitiator, receive = initiatorToResponder)
        }
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
        const val PSK_SIZE = 32

        fun initiator(crypto: NoiseCrypto, psk: ByteArray, prologue: ByteArray, newEphemeral: () -> NoiseKeyPair = crypto::generateKeyPair) =
            NoiseHandshake(crypto, true, psk, prologue, newEphemeral)

        fun responder(crypto: NoiseCrypto, psk: ByteArray, prologue: ByteArray, newEphemeral: () -> NoiseKeyPair = crypto::generateKeyPair) =
            NoiseHandshake(crypto, false, psk, prologue, newEphemeral)
    }
}

/**
 * Messages after the handshake. Each carries its nonce in front (8 bytes), as the spec allows,
 * because the baby unit may skip packets for a slow listener, and a reply waiting for room can be
 * overtaken by a packet sent later. So messages may arrive with gaps and slightly out of order;
 * each nonce is accepted once, within a window of the last [REPLAY_WINDOW] (as IPsec and WireGuard
 * do), and anything else is a replay and is ignored.
 */
class NoiseTransport internal constructor(internal val send: CipherState, internal val receive: CipherState) {
    private var nextNonce = 0L

    /** The highest nonce received, and which of the [REPLAY_WINDOW] before it were: bit i is highest - i. */
    private var highest = -1L
    private var seen = 0L

    sealed interface Opened {
        class Message(val plaintext: ByteArray) : Opened

        /** Seen before, or older than what was already received: drop it. */
        data object Replayed : Opened

        /** Not from the other side, or changed on the way: the connection can't be trusted. */
        data object Forged : Opened
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val nonce = nextNonce++
        return ByteArray(NONCE_SIZE).also { it.putLong(0, nonce) } + send.encryptAt(nonce, plaintext)
    }

    fun open(message: ByteArray): Opened {
        if (message.size < NONCE_SIZE + TAG_SIZE) return Opened.Forged
        val nonce = message.getLong(0)
        if (nonce < 0) return Opened.Forged
        val age = highest - nonce
        if (age >= REPLAY_WINDOW || (age >= 0 && (seen ushr age.toInt()) and 1L == 1L)) return Opened.Replayed
        // Only a message that proves genuine moves the window.
        val plaintext = receive.decryptAt(nonce, message.copyOfRange(NONCE_SIZE, message.size)) ?: return Opened.Forged
        if (age < 0) {
            val shift = -age
            seen = if (shift >= REPLAY_WINDOW) 1L else (seen shl shift.toInt()) or 1L
            highest = nonce
        } else {
            seen = seen or (1L shl age.toInt())
        }
        return Opened.Message(plaintext)
    }

    companion object {
        const val NONCE_SIZE = 8
        const val REPLAY_WINDOW = 64

        /** What sealing adds to every message. */
        const val OVERHEAD = NONCE_SIZE + TAG_SIZE
    }
}

private const val KEY_SIZE = 32
private const val HASH_SIZE = 32
private const val TAG_SIZE = 16
private val EMPTY = ByteArray(0)

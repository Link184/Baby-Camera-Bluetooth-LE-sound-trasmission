package com.link184.babycamera.security

import com.link184.babycamera.link.PairingCode
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Small secrets kept on the device, encrypted by the OS: Android Keystore, iOS Keychain. */
interface SecretStore {
    fun read(name: String): String?

    /** Null removes it. */
    fun write(name: String, value: String?)
}

/**
 * A parent unit the baby unit accepts. Each one has its own key, so removing one doesn't affect the
 * others. Until its first connection it is pending, and a pending one expires after
 * [Pairings.PENDING_MS], so a QR code someone photographed stops working.
 */
class PairedParent(
    val id: String,
    internal val key: ByteArray,
    /** What the parent unit calls itself, e.g. its phone model; null until it first connects. */
    val name: String?,
    val addedAtMillis: Long,
    val confirmed: Boolean,
) {
    val expiresAtMillis: Long? get() = if (confirmed) null else addedAtMillis + Pairings.PENDING_MS
}

/** The baby unit a parent unit listens to. */
class BabyUnit(val unitId: String, internal val key: ByteArray)

/**
 * Who may connect to whom. On the baby unit: its own unit ID and the paired parent units. On a
 * parent unit: the baby unit it scanned. Everything lives in the [SecretStore]. Main thread only.
 */
class Pairings(
    private val store: SecretStore,
    private val nowMillis: () -> Long = ::wallClockMillis,
    private val randomBytes: (Int) -> ByteArray = { CryptographyRandom.Default.nextBytes(it) },
) {
    /** This phone's ID as a baby unit, made the first time it's needed and kept from then on. */
    val ownUnitId: String by lazy {
        store.read(OWN_UNIT) ?: PairingCode.newUnitId().also { store.write(OWN_UNIT, it) }
    }

    private val _parents = MutableStateFlow(decodeParents(store.read(PARENTS)))

    /** Baby unit: confirmed parent units and the pending ones not expired yet. */
    val parents: StateFlow<List<PairedParent>> = _parents

    private val _babyUnit = MutableStateFlow(decodeBabyUnit(store.read(BABY_UNIT)))

    /** Parent unit: the baby unit from the scanned QR code; null until scanned. */
    val babyUnit: StateFlow<BabyUnit?> = _babyUnit

    /** Baby unit: a new key for one more parent unit, as the text of the QR code to show. */
    fun newParentCode(): String {
        val parent = PairedParent(
            id = randomBytes(4).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
            key = randomBytes(PairingCode.KEY_SIZE),
            name = null,
            addedAtMillis = nowMillis(),
            confirmed = false,
        )
        saveParents(unexpired() + parent)
        return PairingCode.format(ownUnitId, parent.key)
    }

    /** Baby unit: the parent units that may connect right now. */
    fun acceptedParents(): List<PairedParent> {
        val current = unexpired()
        if (current.size != _parents.value.size) saveParents(current)
        return current
    }

    /** Baby unit: [id] connected for real, so its key stops expiring. */
    fun confirmParent(id: String, name: String) {
        saveParents(_parents.value.map { if (it.id == id) PairedParent(it.id, it.key, name, it.addedAtMillis, true) else it })
    }

    fun removeParent(id: String) {
        saveParents(_parents.value.filterNot { it.id == id })
    }

    /** Parent unit: remembers the baby unit in a scanned QR code. False if it isn't a BabyCamera code. */
    fun pairWith(scanned: String): Boolean {
        val content = PairingCode.parse(scanned) ?: return false
        val unit = BabyUnit(content.unitId, content.key)
        store.write(BABY_UNIT, "${unit.unitId} ${base64.encode(unit.key)}")
        _babyUnit.value = unit
        return true
    }

    private fun unexpired(): List<PairedParent> {
        val now = nowMillis()
        return _parents.value.filter { parent -> parent.expiresAtMillis.let { it == null || now < it } }
    }

    private fun saveParents(parents: List<PairedParent>) {
        store.write(PARENTS, parents.joinToString("\n", transform = ::encodeParent).ifEmpty { null })
        _parents.value = parents
    }

    // One parent per line: id, 1 or 0 for confirmed, added-at millis, key, name; base64 where needed.
    private fun encodeParent(parent: PairedParent): String = listOf(
        parent.id,
        if (parent.confirmed) "1" else "0",
        parent.addedAtMillis.toString(),
        base64.encode(parent.key),
        parent.name?.let { base64.encode(it.encodeToByteArray()) } ?: "-",
    ).joinToString(" ")

    private fun decodeParents(text: String?): List<PairedParent> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split(' ')
        if (parts.size != 5) return@mapNotNull null
        try {
            PairedParent(
                id = parts[0],
                key = base64.decode(parts[3]).takeIf { it.size == PairingCode.KEY_SIZE } ?: return@mapNotNull null,
                name = parts[4].takeIf { it != "-" }?.let { base64.decode(it).decodeToString() },
                addedAtMillis = parts[2].toLongOrNull() ?: return@mapNotNull null,
                confirmed = parts[1] == "1",
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decodeBabyUnit(text: String?): BabyUnit? {
        val parts = text?.split(' ') ?: return null
        if (parts.size != 2) return null
        val key = try {
            base64.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (key.size == PairingCode.KEY_SIZE) BabyUnit(parts[0], key) else null
    }

    companion object {
        /** How long a code shown on the baby unit can be used for a first connection. */
        const val PENDING_MS = 10 * 60 * 1000L

        private const val OWN_UNIT = "own_unit_id"
        private const val PARENTS = "parents"
        private const val BABY_UNIT = "baby_unit"

        @OptIn(ExperimentalEncodingApi::class)
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    }
}

@OptIn(ExperimentalTime::class)
private fun wallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()

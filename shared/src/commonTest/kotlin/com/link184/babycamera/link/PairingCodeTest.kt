package com.link184.babycamera.link

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingCodeTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val key = ByteArray(PairingCode.KEY_SIZE) { (it * 7).toByte() }

    @Test
    fun codeGivesBackTheUnitIdAndKey() {
        val content = assertNotNull(PairingCode.parse(PairingCode.format(id, key)))
        assertEquals(id, content.unitId)
        assertContentEquals(key, content.key)
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(id, PairingCode.parse("  ${PairingCode.format(id, key)}\n")?.unitId)
    }

    @Test
    fun otherCodesAreRejected() {
        val good = PairingCode.format(id, key)
        assertNull(PairingCode.parse("https://example.com"))
        assertNull(PairingCode.parse(id), "a bare UUID isn't one of ours")
        assertNull(PairingCode.parse("babycamera:unit:$id"), "the old format, without a key")
        assertNull(PairingCode.parse("babycamera:2:$id"), "no key")
        assertNull(PairingCode.parse("babycamera:2:not-a-uuid:${good.substringAfterLast(':')}"))
        assertNull(PairingCode.parse("$good:extra"))
        assertNull(PairingCode.parse(good.dropLast(4)), "key too short")
        assertNull(PairingCode.parse("${good.dropLast(1)}!"), "not base64")
        assertNull(PairingCode.parse("x$good"))
    }

    @Test
    fun newIdsAreValidAndDifferent() {
        val first = PairingCode.newUnitId()
        assertEquals(first, PairingCode.parse(PairingCode.format(first, key))?.unitId)
        assertNotEquals(first, PairingCode.newUnitId())
    }

    @Test
    fun shortNameIsTheLastEightHexDigits() {
        assertEquals("7728-950E", PairingCode.shortName(id))
    }
}

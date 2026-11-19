package com.link184.babycamera.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PairingCodeTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun codeGivesBackTheUnitId() {
        assertEquals(id, PairingCode.parse(PairingCode.format(id)))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(id, PairingCode.parse("  ${PairingCode.format(id)}\n"))
    }

    @Test
    fun otherCodesAreRejected() {
        assertNull(PairingCode.parse("https://example.com"))
        assertNull(PairingCode.parse(id), "a bare UUID isn't one of ours")
        assertNull(PairingCode.parse("babycamera:unit:"))
        assertNull(PairingCode.parse("babycamera:unit:not-a-uuid"))
        assertNull(PairingCode.parse("xbabycamera:unit:$id"))
    }

    @Test
    fun newIdsAreValidAndDifferent() {
        val first = PairingCode.newUnitId()
        assertEquals(first, PairingCode.parse(PairingCode.format(first)))
        assertNotEquals(first, PairingCode.newUnitId())
    }

    @Test
    fun shortNameIsTheLastEightHexDigits() {
        assertEquals("7728-950E", PairingCode.shortName(id))
    }
}

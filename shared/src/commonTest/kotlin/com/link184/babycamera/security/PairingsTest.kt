package com.link184.babycamera.security

import com.link184.babycamera.FakeSecretStore
import com.link184.babycamera.link.PairingCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingsTest {
    private val store = FakeSecretStore()
    private var now = 1_000_000L

    private fun pairings() = Pairings(store, nowMillis = { now })

    @Test
    fun eachCodeHasItsOwnKeyAndTheSameUnitId() {
        val pairings = pairings()
        val first = assertNotNull(PairingCode.parse(pairings.newParentCode()))
        val second = assertNotNull(PairingCode.parse(pairings.newParentCode()))

        assertEquals(pairings.ownUnitId, first.unitId)
        assertEquals(pairings.ownUnitId, second.unitId)
        assertFalse(first.key.contentEquals(second.key))
        assertEquals(2, pairings.acceptedParents().size)
    }

    @Test
    fun unusedCodesExpireButConfirmedParentsStay() {
        val pairings = pairings()
        pairings.newParentCode()
        pairings.newParentCode()
        val used = pairings.parents.value.first()
        pairings.confirmParent(used.id, "Pixel 8a")

        now += Pairings.PENDING_MS - 1
        assertEquals(2, pairings.acceptedParents().size)
        now += 1
        val left = pairings.acceptedParents().single()
        assertEquals(used.id, left.id)
        assertEquals("Pixel 8a", left.name)
        assertEquals(listOf(used.id), pairings.parents.value.map { it.id }, "the expired one is gone for good")
    }

    @Test
    fun everythingSurvivesARestart() {
        val first = pairings()
        val unitId = first.ownUnitId
        first.newParentCode()
        first.confirmParent(first.parents.value.single().id, "Phone with spaces & ünïcode")
        val babyCode = PairingCode.format("0f8fad5b-d9cb-469f-a165-70867728950e", ByteArray(32) { 9 })
        assertTrue(first.pairWith(babyCode))

        val restarted = pairings()
        assertEquals(unitId, restarted.ownUnitId)
        val parent = restarted.parents.value.single()
        assertEquals("Phone with spaces & ünïcode", parent.name)
        assertTrue(parent.confirmed)
        assertContentEquals(first.parents.value.single().key, parent.key)
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", restarted.babyUnit.value?.unitId)
        assertContentEquals(ByteArray(32) { 9 }, restarted.babyUnit.value?.key)
    }

    @Test
    fun removingAParentForgetsItsKey() {
        val pairings = pairings()
        pairings.newParentCode()
        pairings.newParentCode()
        val (gone, kept) = pairings.parents.value
        pairings.removeParent(gone.id)

        assertEquals(listOf(kept.id), pairings.acceptedParents().map { it.id })
        assertEquals(listOf(kept.id), pairings().parents.value.map { it.id })
    }

    @Test
    fun scanningSomethingElseChangesNothing() {
        val pairings = pairings()
        assertFalse(pairings.pairWith("https://example.com"))
        assertNull(pairings.babyUnit.value)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun scanningAgainReplacesTheBabyUnit() {
        val pairings = pairings()
        pairings.pairWith(PairingCode.format("0f8fad5b-d9cb-469f-a165-70867728950e", ByteArray(32) { 1 }))
        pairings.pairWith(PairingCode.format("11111111-2222-4333-8444-555555555555", ByteArray(32) { 2 }))
        assertEquals("11111111-2222-4333-8444-555555555555", pairings().babyUnit.value?.unitId)
    }

    @Test
    fun aDamagedStoreReadsAsEmpty() {
        store.values["parents"] = "garbage\nmore garbage here x y"
        store.values["baby_unit"] = "only-one-part"
        val pairings = pairings()
        assertTrue(pairings.parents.value.isEmpty())
        assertNull(pairings.babyUnit.value)
        assertNotEquals("", pairings.ownUnitId)
    }
}

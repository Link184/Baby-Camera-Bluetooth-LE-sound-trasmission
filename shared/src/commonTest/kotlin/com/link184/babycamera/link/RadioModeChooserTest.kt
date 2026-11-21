package com.link184.babycamera.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioModeChooserTest {

    private var now = 0L
    private val chooser = RadioModeChooser()

    /** One reading a second, like the RSSI poll; the last decision, or null. */
    private fun readings(dbm: Int, count: Int): RadioMode? {
        var decision: RadioMode? = null
        repeat(count) {
            now += 1_000
            chooser.onRssi(dbm, now)?.let { decision = it }
        }
        return decision
    }

    private fun connectedOn(mode: RadioMode) = chooser.onModeReported(mode, now)

    @Test
    fun staysOnNormalWhileTheSignalIsGood() {
        connectedOn(RadioMode.Normal)
        assertNull(readings(-60, 60))
    }

    @Test
    fun switchesToLongRangeWhenTheSignalStaysWeak() {
        connectedOn(RadioMode.Normal)
        assertEquals(RadioMode.LongRange, readings(-85, 10))
        assertEquals(-85, chooser.decidedAtDbm)
    }

    @Test
    fun waitsAfterConnectingBeforeSwitching() {
        connectedOn(RadioMode.Normal)
        assertNull(readings(-90, 9))
        assertEquals(RadioMode.LongRange, readings(-90, 1))
    }

    @Test
    fun oneBadReadingIsNotEnough() {
        connectedOn(RadioMode.Normal)
        readings(-60, 20)
        now += 1_000
        assertNull(chooser.onRssi(-95, now))
        assertNull(readings(-60, 20))
    }

    @Test
    fun goesBackToNormalOnlyOnceClearlyStronger() {
        connectedOn(RadioMode.LongRange)
        assertNull(readings(-74, 30), "between the two thresholds: stays")
        assertEquals(RadioMode.Normal, readings(-62, 5))
    }

    @Test
    fun doesntFlipBackAndForthQuickly() {
        connectedOn(RadioMode.Normal)
        assertEquals(RadioMode.LongRange, readings(-85, 10))
        connectedOn(RadioMode.LongRange)
        assertNull(readings(-60, 9), "held for 10 s after the switch")
        assertEquals(RadioMode.Normal, readings(-60, 1))
    }

    @Test
    fun waitsForTheAnswerBeforeDecidingAgain() {
        connectedOn(RadioMode.Normal)
        assertEquals(RadioMode.LongRange, readings(-85, 10))
        assertNull(readings(-85, 4), "the switch is still pending")
        assertTrue(chooser.longRangeAvailable)
    }

    @Test
    fun aPeerThatRefusesLongRangeIsntAskedAgain() {
        connectedOn(RadioMode.Normal)
        assertEquals(RadioMode.LongRange, readings(-85, 10))
        connectedOn(RadioMode.Normal) // the radio's answer: still normal

        assertFalse(chooser.longRangeAvailable)
        assertNull(readings(-90, 60))
    }

    @Test
    fun aPeerThatNeverAnswersCountsAsRefusing() {
        connectedOn(RadioMode.Normal)
        assertEquals(RadioMode.LongRange, readings(-85, 10))
        readings(-85, 5)

        assertFalse(chooser.longRangeAvailable)
        assertNull(readings(-90, 60))
    }

    @Test
    fun decidesNothingBeforeTheRadioReportsItsMode() {
        assertNull(readings(-95, 30))
    }
}

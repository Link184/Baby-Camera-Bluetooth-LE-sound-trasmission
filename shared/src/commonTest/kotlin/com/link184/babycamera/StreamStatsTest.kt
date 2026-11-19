package com.link184.babycamera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamStatsTest {
    private val ms = 1_000_000L

    /** Feeds packets every [intervalMs] starting at [startMs]; returns the last report. */
    private fun StreamStats.feed(seqs: Iterable<Int>, startMs: Long, intervalMs: Long): String? {
        var report: String? = null
        seqs.forEachIndexed { i, seq -> onPacket(seq, (startMs + i * intervalMs) * ms)?.let { report = it } }
        return report
    }

    @Test
    fun reportsOnlyEveryFiveSeconds() {
        val stats = StreamStats(intervalMs = 20)
        assertNull(stats.feed(0 until 200, startMs = 1, intervalMs = 20), "4 s of packets")

        val report = stats.onPacket(250, 5_001 * ms)
        assertNotNull(report)
    }

    @Test
    fun cleanStreamHasNoDropouts() {
        val report = StreamStats(intervalMs = 20).feed(0..250, startMs = 1, intervalMs = 20)

        assertEquals("251/250 packets in 5s, longest gap 20 ms, dropouts (>250 ms) 0, missing 0", report)
    }

    @Test
    fun countsMissingPacketsAndLongGaps() {
        val stats = StreamStats(intervalMs = 20)
        stats.feed(0 until 100, startMs = 1, intervalMs = 20) // until 1.98 s
        stats.onPacket(110, 2_500 * ms) // 10 packets lost, and 519 ms since the last one
        val report = stats.feed(111..240, startMs = 2_520, intervalMs = 20)

        assertNotNull(report)
        assertTrue("missing 10" in report, report)
        assertTrue("dropouts (>250 ms) 1" in report, report)
        assertTrue("longest gap 519 ms" in report, report)
    }

    @Test
    fun aRestartedSenderStartsFresh() {
        val stats = StreamStats(intervalMs = 20)
        stats.feed(1_000 until 1_100, startMs = 1, intervalMs = 20)
        val report = stats.feed(0..300, startMs = 2_001, intervalMs = 20)

        assertNotNull(report)
        assertTrue("missing 0" in report, report)
    }
}

package com.link184.babycamera

/**
 * Receiver-side stats for a packet stream sent every [intervalMs]. A gap longer than
 * [STALL_MS] between packets is what a listener would hear as a dropout.
 */
internal class StreamStats(private val intervalMs: Long) {
    private var windowStart = 0L
    private var lastArrival = 0L
    private var lastSeq = -1
    private var packets = 0
    private var maxGapMs = 0L
    private var stalls = 0
    private var missing = 0

    fun reset() {
        windowStart = 0
        lastArrival = 0
        lastSeq = -1
        stalls = 0
        missing = 0
    }

    /** Returns a report line every [REPORT_EVERY_MS], otherwise null. */
    fun onPacket(seq: Int, now: Long): String? {
        if (lastSeq >= 0 && seq < lastSeq) reset() // the sender restarted its stream
        if (windowStart == 0L) {
            windowStart = now
            packets = 0
            maxGapMs = 0
        } else {
            val gapMs = (now - lastArrival) / 1_000_000
            if (gapMs > maxGapMs) maxGapMs = gapMs
            if (gapMs > STALL_MS) stalls++
        }
        if (lastSeq >= 0 && seq > lastSeq + 1) missing += seq - lastSeq - 1
        lastSeq = seq
        lastArrival = now
        packets++

        val windowMs = (now - windowStart) / 1_000_000
        if (windowMs < REPORT_EVERY_MS) return null
        val expected = windowMs / intervalMs
        val report = "$packets/$expected packets in ${windowMs / 1000}s, longest gap $maxGapMs ms, " +
            "dropouts (>$STALL_MS ms) $stalls, missing $missing"
        windowStart = now
        packets = 0
        maxGapMs = 0
        return report
    }

    companion object {
        /** A longer gap between packets is heard as a dropout. */
        const val STALL_MS = 250L
        const val REPORT_EVERY_MS = 5_000L
    }
}

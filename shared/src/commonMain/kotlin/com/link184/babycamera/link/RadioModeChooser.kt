package com.link184.babycamera.link

/** The Bluetooth LE radio mode of one connection. */
enum class RadioMode {
    /** 1M (or 2M): the fast mode every phone has, including iPhones. */
    Normal,

    /** LE Coded S8: about 8x slower, but reaches 2-4x further in the open. Android only. */
    LongRange,
}

/**
 * Picks the radio mode for one connection from its signal strength, on the side that can measure
 * it (the joiner). Switches to long range when the signal stays weak, and back to normal once it is
 * clearly strong again; the gap between [toLongRangeAtDbm] and [toNormalAtDbm] and [minHoldMs]
 * keep it from flipping back and forth at the edge. A peer that doesn't take long range when asked,
 * such as an iPhone, isn't asked again on this connection.
 *
 * Times are in milliseconds from any fixed point. Not thread-safe.
 */
class RadioModeChooser(
    private val toLongRangeAtDbm: Int = -80,
    private val toNormalAtDbm: Int = -68,
    /** Readings averaged before deciding, one a second. */
    private val window: Int = 5,
    /** Least time between two switches. */
    private val minHoldMs: Long = 10_000,
    /** How long the peer has to answer a switch before it counts as refused. */
    private val answerTimeoutMs: Long = 5_000,
) {
    /** What the connection uses; null until the radio first says. */
    var current: RadioMode? = null
        private set

    /** False once the peer refused long range. */
    var longRangeAvailable = true
        private set

    /** The average signal the last switch was decided on, for the log. */
    var decidedAtDbm = 0
        private set

    private var requested: RadioMode? = null
    private var requestedAtMs = 0L
    private var lastChangeMs: Long? = null
    private val readings = ArrayDeque<Int>()

    /** The radio reports the connection's mode: at the start, and after every switch. */
    fun onModeReported(mode: RadioMode, nowMs: Long) {
        val asked = requested
        if (asked == RadioMode.LongRange && mode != RadioMode.LongRange) longRangeAvailable = false
        if (asked != null || mode != current) lastChangeMs = nowMs
        requested = null
        current = mode
    }

    /** One signal reading. Returns the mode to switch to, or null to stay. */
    fun onRssi(dbm: Int, nowMs: Long): RadioMode? {
        readings.addLast(dbm)
        while (readings.size > window) readings.removeFirst()

        if (requested != null) {
            if (nowMs - requestedAtMs >= answerTimeoutMs) {
                if (requested == RadioMode.LongRange) longRangeAvailable = false
                requested = null
                lastChangeMs = nowMs
            }
            return null
        }
        val mode = current ?: return null
        if (readings.size < window) return null
        lastChangeMs?.let { if (nowMs - it < minHoldMs) return null }

        val average = readings.average()
        val target = when {
            mode == RadioMode.Normal && longRangeAvailable && average <= toLongRangeAtDbm -> RadioMode.LongRange
            mode == RadioMode.LongRange && average >= toNormalAtDbm -> RadioMode.Normal
            else -> return null
        }
        requested = target
        requestedAtMs = nowMs
        decidedAtDbm = average.toInt()
        readings.clear() // judge the new mode on its own readings
        return target
    }
}

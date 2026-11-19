package com.link184.babycamera

/** Which phone this is. The baby unit hosts the Bluetooth link, the parent unit joins it. */
enum class MonitorRole { Baby, Parent }

/** The microphone on the baby unit. */
interface AudioInput {
    /**
     * Starts capturing. [onSamples] receives 16-bit mono samples at the given rate, on a background
     * thread, until [stop]. Returns why it couldn't start, or null.
     */
    fun start(onSamples: (samples: ShortArray, count: Int, sampleRate: Int) -> Unit): String?

    fun stop()
}

/** The speaker on the parent unit. */
interface AudioOutput {
    /** Returns why it couldn't start, or null. */
    fun start(sampleRate: Int): String?

    /** Queues 16-bit mono samples for playback without blocking; drops them if the queue is full. */
    fun write(samples: ShortArray)

    fun stop()
}

/** What the operating system has to do for the monitor: keep it running, alert the parent, settings. */
interface MonitorSystem {
    /** Keeps the app working with the screen off or another app open. Returns a problem, or null. */
    fun keepRunning(role: MonitorRole): String?

    /** Shown in the ongoing notification where the platform has one. */
    fun updateStatus(role: MonitorRole, text: String)

    fun stopRunning()

    /** The baby is crying: notify the parent and vibrate for a long time. */
    fun cryAlert()

    fun clearAlert()

    fun loadThreshold(): Float?

    fun saveThreshold(value: Float)
}

/** Asks for the camera, which only the parent unit needs, to scan the baby unit's QR code. */
fun interface CameraPermission {
    /** [onResult] is called on the main thread. */
    fun request(onResult: (granted: Boolean) -> Unit)
}

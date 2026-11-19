package com.link184.babycamera.watch

/**
 * WatchConnectivity, implemented in Swift (WatchBridge.swift), kept to plain types so Swift can
 * implement it easily. Main thread only.
 */
interface IosWatchBridge {
    fun start(listener: IosWatchListener)

    fun stop()

    /** [PUBLISH_SENT], [PUBLISH_RETRY] or [PUBLISH_UNSUPPORTED]. */
    fun publish(listening: Boolean, connected: Boolean, status: String, crying: Boolean, threshold: Float): Int

    fun isWatching(): Boolean

    /** From 0 to 1, or negative for none. */
    fun sendLevel(level: Float)

    companion object {
        const val PUBLISH_SENT = 0
        const val PUBLISH_RETRY = 1
        const val PUBLISH_UNSUPPORTED = 2
    }
}

interface IosWatchListener {
    /** The parent dismissed the crying alert on the watch. */
    fun onDismiss()
}

/** The shared [WatchMirror]'s link to the Apple Watch app, through the Swift [bridge]. */
class IosWatchLink(private val bridge: IosWatchBridge) : WatchLink {
    override fun start(onDismiss: () -> Unit) {
        bridge.start(object : IosWatchListener {
            override fun onDismiss() = onDismiss()
        })
    }

    override fun stop() = bridge.stop()

    override suspend fun publish(state: WatchState): WatchPublish =
        when (bridge.publish(state.listening, state.connected, state.status, state.crying, state.threshold)) {
            IosWatchBridge.PUBLISH_SENT -> WatchPublish.Sent
            IosWatchBridge.PUBLISH_UNSUPPORTED -> WatchPublish.Unsupported
            else -> WatchPublish.Retry
        }

    override val watching: Boolean get() = bridge.isWatching()

    override suspend fun sendLevel(level: Float?) = bridge.sendLevel(level ?: -1f)
}

package com.link184.babycamera.watch

import com.link184.babycamera.MonitorController
import com.link184.babycamera.MonitorRole
import com.link184.babycamera.link.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the watch app shows of the parent unit. */
data class WatchState(
    /** The phone is running as the parent unit. */
    val listening: Boolean,
    /** ...and is connected to the baby unit. */
    val connected: Boolean,
    /** One short line for the watch, e.g. "Listening". */
    val status: String,
    val crying: Boolean,
    /** The level that counts as loud, from 0 to 1, as on the phone's slider. */
    val threshold: Float,
)

enum class WatchPublish {
    Sent,

    /** Not now (e.g. no watch app installed yet): offered again a little later. */
    Retry,

    /** This phone can't have a watch app at all, so the mirror stops. */
    Unsupported,
}

/**
 * The phone's link to the BabyCamera app on its paired watch: the Wear OS Data Layer on Android,
 * WatchConnectivity on iOS. Both run over the phone's own encrypted link to its watch, so the watch
 * never touches the baby unit or its keys.
 */
interface WatchLink {
    /** Starts listening to the watch app; [onDismiss] when the parent dismisses the alert there. */
    fun start(onDismiss: () -> Unit)

    fun stop()

    /** Stores [state] where the watch app reads it, now or when it next opens. */
    suspend fun publish(state: WatchState): WatchPublish

    /** Whether a watch shows the meter right now, and so wants [sendLevel]. */
    val watching: Boolean

    /** The sound level from 0 to 1, or null when there is none. Lost if the watch is out of reach. */
    suspend fun sendLevel(level: Float?)
}

/**
 * Mirrors the parent unit to the watch app: the [WatchState] whenever it changes, and the sound level
 * every [LEVEL_INTERVAL_MS] while a watch shows it (the level messages also tell the watch that the
 * phone app is alive). The crying alert itself reaches the watch anyway, as the phone's notification,
 * which Wear OS and watchOS both mirror. Main thread only.
 */
class WatchMirror(
    private val controller: MonitorController,
    private val link: WatchLink,
    private val scope: CoroutineScope,
) {
    private var published: WatchState? = null

    fun start(): Job {
        link.start(onDismiss = ::dismissAlert)
        return scope.launch {
            while (true) {
                val state = controller.watchState()
                if (state != published) {
                    when (link.publish(state)) {
                        WatchPublish.Sent -> published = state
                        WatchPublish.Retry -> Unit
                        WatchPublish.Unsupported -> {
                            link.stop()
                            return@launch
                        }
                    }
                }
                if (link.watching) {
                    link.sendLevel(if (state.listening) controller.level else null)
                    delay(LEVEL_INTERVAL_MS)
                } else {
                    delay(IDLE_INTERVAL_MS)
                }
            }
        }
    }

    private fun dismissAlert() {
        if (controller.crying) controller.dismissAlert()
    }

    companion object {
        const val LEVEL_INTERVAL_MS = 250L

        /** How often the state is checked while no watch shows the meter. */
        const val IDLE_INTERVAL_MS = 1_000L
    }
}

internal fun MonitorController.watchState(): WatchState {
    val role = session?.role
    val link = state.value
    val listening = role == MonitorRole.Parent
    return WatchState(
        listening = listening,
        connected = listening && link is LinkState.Connected,
        status = when {
            role == MonitorRole.Baby -> "This phone is the baby unit"
            !listening -> "Not listening"
            link is LinkState.Connected -> "Listening"
            link is LinkState.Failed -> "Connection lost, reconnecting"
            else -> "Connecting to the baby unit"
        },
        crying = listening && crying,
        threshold = threshold,
    )
}

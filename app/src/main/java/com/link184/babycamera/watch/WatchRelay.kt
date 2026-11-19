package com.link184.babycamera.watch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.link184.babycamera.MonitorController
import com.link184.babycamera.MonitorRole
import com.link184.babycamera.link.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Mirrors the parent unit to the BabyCamera watch app (see [WatchProtocol]). The Data Layer runs
 * over the phone's own, already encrypted connection to its paired watch, so nothing here touches
 * the baby unit or its keys: the watch sees only the state and the sound level.
 *
 * The crying alert needs none of this: Wear OS mirrors the phone's notification to the watch, and
 * dismissing it on either side clears both. The watch app adds the live meter and a Dismiss button.
 *
 * On a phone without Wear OS the calls fail, and the relay stops. Main thread only.
 */
class WatchRelay(context: Context, private val controller: MonitorController, private val scope: CoroutineScope) {
    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)

    /** Watches showing the app, by node ID, with the elapsed-realtime time until which they get the level. */
    private val watchers = mutableMapOf<String, Long>()
    private var published: WatchProtocol.State? = null

    private val listener = MessageClient.OnMessageReceivedListener(::onMessage)

    fun start() {
        messageClient.addListener(listener)
        scope.launch {
            while (true) {
                if (!publishState()) {
                    messageClient.removeListener(listener)
                    return@launch
                }
                val watching = activeWatchers()
                if (watching.isEmpty()) {
                    delay(IDLE_INTERVAL_MS)
                } else {
                    val level = WatchProtocol.encodeLevel(controller.level)
                    for (node in watching) send(node, level)
                    delay(WatchProtocol.LEVEL_INTERVAL_MS)
                }
            }
        }
    }

    private fun onMessage(event: MessageEvent) {
        when (event.path) {
            WatchProtocol.WATCHING_PATH -> watchers[event.sourceNodeId] = SystemClock.elapsedRealtime() + WatchProtocol.WATCHING_FOR_MS
            WatchProtocol.STOPPED_WATCHING_PATH -> watchers.remove(event.sourceNodeId)
            WatchProtocol.DISMISS_PATH -> if (controller.crying) controller.dismissAlert()
        }
    }

    private fun activeWatchers(): List<String> {
        val now = SystemClock.elapsedRealtime()
        watchers.entries.removeAll { it.value <= now }
        return watchers.keys.toList()
    }

    /** Sends the state when it changed. False if this phone has no Wear OS, so there's no point going on. */
    private suspend fun publishState(): Boolean {
        val state = currentState()
        if (state == published) return true
        val request = PutDataMapRequest.create(WatchProtocol.STATE_PATH)
            .apply { state.writeTo(dataMap) }
            .asPutDataRequest()
            .setUrgent()
        try {
            dataClient.putDataItem(request).await()
            published = state
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.API_NOT_CONNECTED) {
                Log.i(TAG, "No Wear OS on this phone; the watch relay is off")
                return false
            }
            Log.w(TAG, "Couldn't update the watch: ${e.message}") // tried again next time
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update the watch: ${e.message}")
        }
        return true
    }

    private suspend fun send(node: String, payload: ByteArray) {
        try {
            messageClient.sendMessage(node, WatchProtocol.LEVEL_PATH, payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            watchers.remove(node) // out of reach; it asks again when it can
        }
    }

    private fun currentState(): WatchProtocol.State {
        val role = controller.session?.role
        val link = controller.state.value
        val listening = role == MonitorRole.Parent
        return WatchProtocol.State(
            listening = listening,
            connected = listening && link is LinkState.Connected,
            status = when {
                role == MonitorRole.Baby -> "This phone is the baby unit"
                !listening -> "Not listening"
                link is LinkState.Connected -> "Listening"
                link is LinkState.Failed -> "Connection lost, reconnecting"
                else -> "Connecting to the baby unit"
            },
            crying = listening && controller.crying,
            threshold = controller.threshold,
        )
    }

    private companion object {
        const val TAG = "WatchRelay"

        /** How often the state is checked while no watch shows the app. */
        const val IDLE_INTERVAL_MS = 1_000L
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The shared [WatchMirror]'s link to the Wear OS watch app, over the Data Layer (see [WatchProtocol]).
 * On a phone without Wear OS the calls fail, and the mirror stops. Main thread only.
 */
class WearWatchLink(context: Context) : WatchLink {
    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)

    /** Watches showing the app, by node ID, with the elapsed-realtime time until which they get the level. */
    private val watchers = mutableMapOf<String, Long>()
    private var onDismiss: () -> Unit = {}

    private val listener = MessageClient.OnMessageReceivedListener(::onMessage)

    override fun start(onDismiss: () -> Unit) {
        this.onDismiss = onDismiss
        messageClient.addListener(listener)
    }

    override fun stop() {
        messageClient.removeListener(listener)
        watchers.clear()
    }

    override val watching: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            watchers.entries.removeAll { it.value <= now }
            return watchers.isNotEmpty()
        }

    override suspend fun publish(state: WatchState): WatchPublish {
        val request = PutDataMapRequest.create(WatchProtocol.STATE_PATH)
            .apply {
                WatchProtocol.State(state.listening, state.connected, state.status, state.crying, state.threshold)
                    .writeTo(dataMap)
            }
            .asPutDataRequest()
            .setUrgent()
        return try {
            dataClient.putDataItem(request).await()
            WatchPublish.Sent
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.API_NOT_CONNECTED) {
                Log.i(TAG, "No Wear OS on this phone; the watch link is off")
                WatchPublish.Unsupported
            } else {
                Log.w(TAG, "Couldn't update the watch: ${e.message}")
                WatchPublish.Retry
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update the watch: ${e.message}")
            WatchPublish.Retry
        }
    }

    override suspend fun sendLevel(level: Float?) {
        val payload = WatchProtocol.encodeLevel(level)
        for (node in watchers.keys.toList()) {
            try {
                messageClient.sendMessage(node, WatchProtocol.LEVEL_PATH, payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                watchers.remove(node) // out of reach; it asks again when it can
            }
        }
    }

    private fun onMessage(event: MessageEvent) {
        when (event.path) {
            WatchProtocol.WATCHING_PATH -> watchers[event.sourceNodeId] = SystemClock.elapsedRealtime() + WatchProtocol.WATCHING_FOR_MS
            WatchProtocol.STOPPED_WATCHING_PATH -> watchers.remove(event.sourceNodeId)
            WatchProtocol.DISMISS_PATH -> onDismiss()
        }
    }

    private companion object {
        const val TAG = "WearWatchLink"
    }
}

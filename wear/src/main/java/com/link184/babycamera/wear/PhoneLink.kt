package com.link184.babycamera.wear

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.link184.babycamera.watch.WatchProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The watch's side of [WatchProtocol]: follows the phone app's parent unit while the screen shows it.
 * Call [resume] and [pause] with the activity. Main thread only.
 */
class PhoneLink(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val player = SoundPlayer()
    private var resumed = false

    /** Whether the watch plays the sound too; remembered. */
    var soundOn by mutableStateOf(prefs.getBoolean(KEY_SOUND, false))
        private set

    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    /** The parent unit on the phone; null until the phone has sent it once. */
    var state by mutableStateOf<WatchProtocol.State?>(null)
        private set

    /** Sound level from 0 to 1; null when the phone hears nothing (not connected). */
    var level by mutableStateOf<Float?>(null)
        private set

    /** Elapsed realtime of the last message from the phone app; null before the first. */
    var lastHeardAt by mutableStateOf<Long?>(null)
        private set

    /** Whether the phone is in Bluetooth range of the watch; null until checked. */
    var phoneInReach by mutableStateOf<Boolean?>(null)
        private set

    private var job: Job? = null

    private val dataListener = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchProtocol.STATE_PATH) {
                state = WatchProtocol.State.from(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        }
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == WatchProtocol.LEVEL_PATH) {
            level = WatchProtocol.decodeLevel(event.data)
            lastHeardAt = SystemClock.elapsedRealtime()
            if (soundOn) WatchProtocol.decodeSound(event.data).forEach(player::play)
        }
    }

    fun resume() {
        resumed = true
        if (soundOn) player.start()
        dataClient.addListener(dataListener)
        messageClient.addListener(messageListener)
        job = scope.launch {
            loadState()
            // Keep telling the phone we're watching, so it keeps sending the level.
            while (true) {
                val phones = phones()
                phoneInReach = phones.isNotEmpty()
                for (phone in phones) send(phone, WatchProtocol.WATCHING_PATH, WatchProtocol.encodeWatching(soundOn))
                delay(WatchProtocol.WATCHING_PING_MS)
            }
        }
    }

    fun pause() {
        resumed = false
        player.stop()
        job?.cancel()
        dataClient.removeListener(dataListener)
        messageClient.removeListener(messageListener)
        scope.launch { for (phone in phones()) send(phone, WatchProtocol.STOPPED_WATCHING_PATH) }
    }

    fun toggleSound() {
        soundOn = !soundOn
        prefs.edit().putBoolean(KEY_SOUND, soundOn).apply()
        if (!resumed) return
        if (soundOn) player.start() else player.stop()
        val watching = WatchProtocol.encodeWatching(soundOn)
        scope.launch { for (phone in phones()) send(phone, WatchProtocol.WATCHING_PATH, watching) }
    }

    fun dismissAlert() {
        scope.launch { for (phone in phones()) send(phone, WatchProtocol.DISMISS_PATH) }
    }

    /** The latest state the phone sent, possibly while this app wasn't open. */
    private suspend fun loadState() {
        val items = try {
            dataClient.dataItems.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        try {
            items.firstOrNull { it.uri.path == WatchProtocol.STATE_PATH }?.let {
                state = WatchProtocol.State.from(DataMapItem.fromDataItem(it).dataMap)
            }
        } finally {
            items.release()
        }
    }

    private suspend fun phones(): List<String> = try {
        nodeClient.connectedNodes.await().map { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun send(node: String, path: String, data: ByteArray = ByteArray(0)) {
        try {
            messageClient.sendMessage(node, path, data).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Out of reach: phoneInReach shows it on the next round.
        }
    }
}

private const val KEY_SOUND = "sound"

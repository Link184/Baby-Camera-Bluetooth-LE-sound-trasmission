package com.link184.babycamera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.setActive
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.Foundation.NSUserDefaults
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS keeps the app running through its background modes (audio and Bluetooth, see Info.plist),
 * so there is no service here. The phone's vibration motor only does short buzzes, so a long
 * vibration is several in a row.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMonitorSystem : MonitorSystem {

    private val scope = MainScope()
    private val defaults = NSUserDefaults.standardUserDefaults
    private val notifications = UNUserNotificationCenter.currentNotificationCenter()
    private var vibration: Job? = null

    override fun keepRunning(role: MonitorRole): String? {
        if (role == MonitorRole.Parent) {
            notifications.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
        }
        return null
    }

    override fun updateStatus(role: MonitorRole, text: String) = Unit

    override fun stopRunning() {
        AVAudioSession.sharedInstance().setActive(false, error = null)
    }

    override fun cryAlert() {
        val content = UNMutableNotificationContent().apply {
            setTitle("The baby is crying")
            setBody("The sound stayed above your alert level")
            setSound(UNNotificationSound.defaultSound)
        }
        notifications.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(ALERT_ID, content, trigger = null),
            withCompletionHandler = null,
        )
        vibration?.cancel()
        vibration = scope.launch {
            repeat(VIBRATIONS) {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                delay(1_000)
            }
        }
    }

    override fun clearAlert() {
        vibration?.cancel()
        notifications.removeDeliveredNotificationsWithIdentifiers(listOf(ALERT_ID))
    }

    override fun loadThreshold(): Float? =
        if (defaults.objectForKey(KEY_THRESHOLD) != null) defaults.floatForKey(KEY_THRESHOLD) else null

    override fun saveThreshold(value: Float) {
        defaults.setFloat(value, forKey = KEY_THRESHOLD)
    }

    override fun loadText(key: String): String? = defaults.stringForKey(key)

    override fun saveText(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }

    private companion object {
        const val ALERT_ID = "crying"
        const val KEY_THRESHOLD = "threshold"
        const val VIBRATIONS = 6
    }
}

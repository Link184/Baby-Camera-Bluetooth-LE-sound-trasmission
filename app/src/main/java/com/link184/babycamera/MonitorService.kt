package com.link184.babycamera

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the app alive and allowed to use the microphone, speaker and Bluetooth while it's in the
 * background. The monitor itself lives in [BabyCameraApp.controller]; this only holds the
 * foreground notification and a wake lock.
 */
class MonitorService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BabyCameraApp
        if (intent?.action == ACTION_STOP) {
            app.controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires startForeground() even if the monitor was stopped in the meantime.
        val role = app.system.role
        val inForeground = goForeground(app.system.runningNotification(), role)
        if (!inForeground || role == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabyCamera:monitor")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    /** Tries the full set of service types first, then without Bluetooth if its permission is missing. */
    private fun goForeground(notification: Notification, role: MonitorRole?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(AndroidMonitorSystem.RUNNING_ID, notification)
            return true
        }
        val audio = if (role == MonitorRole.Baby) {
            // Android 10 has no microphone type; any foreground service may record there.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        val attempts = listOf(
            audio or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            audio,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        for (types in attempts) {
            try {
                startForeground(AndroidMonitorSystem.RUNNING_ID, notification, types)
                return true
            } catch (e: RuntimeException) { // SecurityException, or not allowed from the background
                Log.w(TAG, "startForeground(types=$types) refused", e)
            }
        }
        return false
    }

    companion object {
        const val ACTION_STOP = "com.link184.babycamera.STOP"
        private const val TAG = "BabyCamMonitor"

        /** A long night; the monitor releases it earlier when stopped. */
        private const val WAKE_LOCK_TIMEOUT_MS = 16 * 60 * 60 * 1_000L
    }
}

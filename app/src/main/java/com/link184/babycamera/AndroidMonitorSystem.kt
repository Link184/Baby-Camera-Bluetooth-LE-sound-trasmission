package com.link184.babycamera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit

/** Foreground service, notifications, vibration and settings for [MonitorController]. */
class AndroidMonitorSystem(private val context: Context) : MonitorSystem {

    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)

    /** Role of the running monitor, read by [MonitorService]; null when stopped. */
    var role: MonitorRole? = null
        private set
    private var status = "Starting"

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Monitor running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the baby or parent unit is on"
                setShowBadge(false)
            },
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_CRYING, "Baby is crying", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When the sound stays above your alert level"
                enableVibration(false) // the app vibrates by itself, longer than a notification can
            },
        )
    }

    override fun keepRunning(role: MonitorRole): String? {
        this.role = role
        status = "Starting"
        return try {
            context.startForegroundService(Intent(context, MonitorService::class.java))
            null
        } catch (e: IllegalStateException) { // not allowed from the background
            e.message ?: e.toString()
        } catch (e: SecurityException) {
            e.message ?: e.toString()
        }
    }

    override fun updateStatus(role: MonitorRole, text: String) {
        if (this.role == null) return
        status = text
        post(RUNNING_ID, runningNotification())
    }

    override fun stopRunning() {
        if (role == null) return
        role = null
        context.stopService(Intent(context, MonitorService::class.java))
    }

    override fun cryAlert() {
        post(
            CRYING_ID,
            NotificationCompat.Builder(context, CHANNEL_CRYING)
                .setSmallIcon(R.drawable.ic_stat_crying)
                .setColor(context.getColor(R.color.notification_accent))
                .setContentTitle("The baby is crying")
                .setContentText("The sound stayed above your alert level")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build(),
        )
        vibrateLong()
    }

    override fun clearAlert() {
        notifications.cancel(CRYING_ID)
        vibrator().cancel()
    }

    override fun loadThreshold(): Float? =
        if (prefs.contains(KEY_THRESHOLD)) prefs.getFloat(KEY_THRESHOLD, 0f) else null

    override fun saveThreshold(value: Float) {
        prefs.edit { putFloat(KEY_THRESHOLD, value) }
    }

    fun runningNotification(): Notification {
        val title = when (role) {
            MonitorRole.Baby -> "Baby unit is listening"
            MonitorRole.Parent -> "Parent unit is on"
            null -> "BabyCamera"
        }
        val stop = PendingIntent.getService(
            context,
            0,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Without the notification permission this shows nothing, which is fine: the vibration still works. */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        try {
            notifications.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post notification", e)
        }
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Alarm usage, so it also vibrates in silent mode and from the background. */
    private fun vibrateLong() {
        val vibrator = vibrator()
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    companion object {
        const val RUNNING_ID = 1
        private const val CRYING_ID = 2
        private const val CHANNEL_RUNNING = "running"
        private const val CHANNEL_CRYING = "crying"
        private const val KEY_THRESHOLD = "threshold"
        private const val TAG = "BabyCamMonitor"

        /** About 6 seconds: three long buzzes and a longer one. */
        private val VIBRATION_PATTERN = longArrayOf(0, 1_200, 400, 1_200, 400, 1_200, 400, 2_000)
    }
}

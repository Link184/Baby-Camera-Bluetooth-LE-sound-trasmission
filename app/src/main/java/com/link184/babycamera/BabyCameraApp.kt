package com.link184.babycamera

import android.app.Application
import android.os.Build
import com.link184.babycamera.audio.AndroidAudioInput
import com.link184.babycamera.audio.AndroidAudioOutput
import com.link184.babycamera.link.AndroidBleLink
import com.link184.babycamera.security.Pairings
import com.link184.babycamera.security.SecureLink
import com.link184.babycamera.security.androidNoiseCrypto
import kotlinx.coroutines.MainScope

/**
 * Owns the monitor for the whole process, so it keeps running after the screen is closed;
 * [MonitorService] keeps the process alive meanwhile.
 */
class BabyCameraApp : Application() {

    val system by lazy { AndroidMonitorSystem(this) }

    val controller by lazy {
        val scope = MainScope()
        val pairings = Pairings(AndroidSecretStore(this))
        MonitorController(
            link = SecureLink(AndroidBleLink(this), pairings, androidNoiseCrypto(), scope, deviceName = Build.MODEL),
            microphone = AndroidAudioInput(this),
            speaker = AndroidAudioOutput(),
            system = system,
            pairings = pairings,
            scope = scope,
        )
    }
}

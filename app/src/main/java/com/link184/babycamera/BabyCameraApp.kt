package com.link184.babycamera

import android.app.Application
import com.link184.babycamera.audio.AndroidAudioInput
import com.link184.babycamera.audio.AndroidAudioOutput
import com.link184.babycamera.link.AndroidBleLink
import kotlinx.coroutines.MainScope

/**
 * Owns the monitor for the whole process, so it keeps running after the screen is closed;
 * [MonitorService] keeps the process alive meanwhile.
 */
class BabyCameraApp : Application() {

    val system by lazy { AndroidMonitorSystem(this) }

    val controller by lazy {
        MonitorController(
            link = AndroidBleLink(this),
            microphone = AndroidAudioInput(this),
            speaker = AndroidAudioOutput(),
            system = system,
            scope = MainScope(),
        )
    }
}

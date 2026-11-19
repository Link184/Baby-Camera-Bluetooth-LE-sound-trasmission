package com.link184.babycamera

import androidx.compose.ui.window.ComposeUIViewController
import com.link184.babycamera.audio.IosAudioInput
import com.link184.babycamera.audio.IosAudioOutput
import com.link184.babycamera.link.IosBleBridge
import com.link184.babycamera.link.IosBleLink
import kotlinx.coroutines.MainScope
import platform.UIKit.UIViewController

/** Entry point called from Swift: hosts the shared Compose UI on top of the Swift Bluetooth bridge. */
fun MainViewController(bridge: IosBleBridge): UIViewController {
    val controller = MonitorController(
        link = IosBleLink(bridge),
        microphone = IosAudioInput(),
        speaker = IosAudioOutput(),
        system = IosMonitorSystem(),
        scope = MainScope(),
    )
    return ComposeUIViewController { App(controller) }
}

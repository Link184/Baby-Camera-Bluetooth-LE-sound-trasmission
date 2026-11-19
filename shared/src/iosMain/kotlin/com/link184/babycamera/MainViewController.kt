package com.link184.babycamera

import androidx.compose.ui.window.ComposeUIViewController
import com.link184.babycamera.audio.IosAudioInput
import com.link184.babycamera.audio.IosAudioOutput
import com.link184.babycamera.link.IosBleBridge
import com.link184.babycamera.link.IosBleLink
import kotlinx.coroutines.MainScope
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Entry point called from Swift: hosts the shared Compose UI on top of the Swift Bluetooth bridge. */
fun MainViewController(bridge: IosBleBridge): UIViewController {
    val controller = MonitorController(
        link = IosBleLink(bridge),
        microphone = IosAudioInput(),
        speaker = IosAudioOutput(),
        system = IosMonitorSystem(),
        scope = MainScope(),
    )
    return ComposeUIViewController { App(controller, IosCameraPermission) }
}

private val IosCameraPermission = CameraPermission { onResult ->
    when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> onResult(true)
        AVAuthorizationStatusNotDetermined ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
            }
        else -> onResult(false) // denied earlier: only the Settings app can change it
    }
}

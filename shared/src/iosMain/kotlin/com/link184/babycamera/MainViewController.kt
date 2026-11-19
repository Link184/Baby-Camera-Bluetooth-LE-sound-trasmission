package com.link184.babycamera

import androidx.compose.ui.window.ComposeUIViewController
import com.link184.babycamera.audio.IosAudioInput
import com.link184.babycamera.audio.IosAudioOutput
import com.link184.babycamera.link.IosBleBridge
import com.link184.babycamera.link.IosBleLink
import com.link184.babycamera.security.NoiseCrypto
import com.link184.babycamera.security.Pairings
import com.link184.babycamera.security.SecretStore
import com.link184.babycamera.security.SecureLink
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import kotlinx.coroutines.MainScope
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Entry point called from Swift: hosts the shared Compose UI on top of the Swift Bluetooth bridge.
 * [secrets] is the Swift Keychain store.
 */
fun MainViewController(bridge: IosBleBridge, secrets: SecretStore): UIViewController {
    val scope = MainScope()
    val pairings = Pairings(secrets)
    val controller = MonitorController(
        link = SecureLink(
            IosBleLink(bridge),
            pairings,
            NoiseCrypto(CryptographyProvider.CryptoKit),
            scope,
            deviceName = UIDevice.currentDevice.model,
        ),
        microphone = IosAudioInput(),
        speaker = IosAudioOutput(),
        system = IosMonitorSystem(),
        pairings = pairings,
        scope = scope,
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

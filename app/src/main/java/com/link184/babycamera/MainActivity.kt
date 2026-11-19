package com.link184.babycamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.link184.babycamera.link.requiredBlePermissions

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* checked again on start */ }

    /** Asked only when the user scans a QR code, since only parent units need the camera. */
    private var onCameraResult: ((Boolean) -> Unit)? = null
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onCameraResult?.invoke(granted)
        onCameraResult = null
    }

    private val cameraPermission = CameraPermission { onResult ->
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            onCameraResult = onResult
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestPermissions.launch(
            buildList {
                addAll(requiredBlePermissions())
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray(),
        )
        // The monitor belongs to the app, not this screen: closing the screen doesn't stop it.
        val controller = (application as BabyCameraApp).controller
        setContent { App(controller, cameraPermission) }
    }
}

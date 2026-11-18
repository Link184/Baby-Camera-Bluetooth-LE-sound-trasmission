package com.link184.babycamera

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.link184.babycamera.link.AndroidBleLink
import com.link184.babycamera.link.requiredBlePermissions

class MainActivity : ComponentActivity() {

    private val link by lazy { AndroidBleLink(applicationContext) }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* the link re-checks on start */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions.launch(requiredBlePermissions())
        setContent { App(link) }
    }

    override fun onDestroy() {
        if (isFinishing) link.stop()
        super.onDestroy()
    }
}

package com.link184.babycamera

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.link184.babycamera.link.IosBleBridge
import com.link184.babycamera.link.IosBleLink
import platform.UIKit.UIViewController

/** Entry point called from Swift: hosts the shared Compose UI on top of the Swift Bluetooth bridge. */
fun MainViewController(bridge: IosBleBridge): UIViewController =
    ComposeUIViewController { App(remember { IosBleLink(bridge) }) }

package io.github.yearsyan.pi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
actual fun KeepScreenOn(active: Boolean) {
    DisposableEffect(active) {
        UIApplication.sharedApplication.idleTimerDisabled = active
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

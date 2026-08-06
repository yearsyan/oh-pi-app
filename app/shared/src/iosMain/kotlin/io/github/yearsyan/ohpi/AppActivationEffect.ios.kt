package io.github.yearsyan.ohpi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification

@Composable
internal actual fun AppActivationEffect(
    onInactive: () -> Unit,
    onActive: () -> Unit,
) {
    val currentOnInactive = rememberUpdatedState(onInactive)
    val currentOnActive = rememberUpdatedState(onActive)

    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val inactiveObserver =
            center.addObserverForName(
                name = UIApplicationWillResignActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                currentOnInactive.value()
            }
        val activeObserver =
            center.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) {
                currentOnActive.value()
            }

        onDispose {
            center.removeObserver(inactiveObserver)
            center.removeObserver(activeObserver)
        }
    }
}

internal actual fun isApplicationActive(): Boolean =
    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

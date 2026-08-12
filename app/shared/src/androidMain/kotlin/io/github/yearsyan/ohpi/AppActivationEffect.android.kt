package io.github.yearsyan.ohpi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.concurrent.Volatile

@Volatile
private var applicationActive = true

@Composable
internal actual fun AppActivationEffect(
    onInactive: () -> Unit,
    onActive: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnInactive = rememberUpdatedState(onInactive)
    val currentOnActive = rememberUpdatedState(onActive)

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        applicationActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    applicationActive = true
                    currentOnActive.value()
                }
                Lifecycle.Event.ON_STOP -> {
                    applicationActive = false
                    currentOnInactive.value()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

internal actual fun isApplicationActive(): Boolean = applicationActive

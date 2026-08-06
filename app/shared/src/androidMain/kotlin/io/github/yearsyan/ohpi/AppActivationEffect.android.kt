package io.github.yearsyan.ohpi

import androidx.compose.runtime.Composable

@Composable
internal actual fun AppActivationEffect(
    onInactive: () -> Unit,
    onActive: () -> Unit,
) = Unit

internal actual fun isApplicationActive(): Boolean = true

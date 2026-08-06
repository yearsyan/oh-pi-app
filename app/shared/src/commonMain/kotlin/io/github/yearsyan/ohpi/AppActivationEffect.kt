package io.github.yearsyan.ohpi

import androidx.compose.runtime.Composable

/** Reports application focus changes that can interrupt an in-flight platform permission request. */
@Composable
internal expect fun AppActivationEffect(
    onInactive: () -> Unit,
    onActive: () -> Unit,
)

/** Whether the application currently has focus and can present connection feedback. */
internal expect fun isApplicationActive(): Boolean

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable

/**
 * Keeps the screen awake while [active] is true. Used on the chat screen so the
 * display does not turn off while the model is generating output. Mobile-only:
 * the desktop actual is a no-op.
 */
@Composable
expect fun KeepScreenOn(active: Boolean)

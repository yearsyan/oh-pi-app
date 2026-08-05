package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable

// No system idle timeout to manage on desktop.
@Composable
actual fun KeepScreenOn(active: Boolean) = Unit

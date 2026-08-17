package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable

/** Native glass chrome only exists on iOS; other platforms fade with the scene. */
@Composable
internal actual fun SyncGlassWithBackGesture(
    controller: GlassExitController,
    isActive: () -> Boolean,
) = Unit

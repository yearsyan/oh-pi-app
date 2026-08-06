package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** Desktop windows have no system bars; nothing to do. */
@Composable
internal actual fun ImmersiveDialogWindowEffect() = Unit

/** Desktop windows have no system bars; no edge-to-edge opt-in exists. */
internal actual fun immersiveDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** iOS dialogs already span the full screen, including the status-bar area. */
@Composable
internal actual fun ImmersiveDialogWindowEffect() = Unit

/** iOS dialogs already span the full screen; no edge-to-edge opt-in exists. */
internal actual fun immersiveDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

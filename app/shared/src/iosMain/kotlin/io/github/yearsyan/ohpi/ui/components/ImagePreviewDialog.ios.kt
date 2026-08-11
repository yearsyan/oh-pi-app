package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DialogProperties

/** The dialog layer itself needs no additional UIKit window configuration. */
@Composable
internal actual fun ImmersiveDialogWindowEffect() = Unit

/** Let the viewer layer cover the status-bar and home-indicator safe areas. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun immersiveDialogProperties(): DialogProperties =
    DialogProperties(
        usePlatformDefaultWidth = false,
        usePlatformInsets = false,
    )

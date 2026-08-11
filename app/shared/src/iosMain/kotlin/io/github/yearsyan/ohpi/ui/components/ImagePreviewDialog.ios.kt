package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Hosts the viewer in a full-screen dialog; iOS dialogs already span the full
 * screen. Platform insets are disabled so the viewer also covers the status
 * bar and home-indicator areas. Single-tap dismissal is enabled for the touch
 * UI.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun ImagePreviewContainer(
    onDismiss: () -> Unit,
    title: String?,
    content: @Composable (dismissOnTap: Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                usePlatformInsets = false,
            ),
    ) {
        content(true)
    }
}

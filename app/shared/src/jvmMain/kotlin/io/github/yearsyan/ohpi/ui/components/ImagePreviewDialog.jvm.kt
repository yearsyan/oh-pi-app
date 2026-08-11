package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.yearsyan.ohpi.desktopAppIconPainter
import io.github.yearsyan.ohpi.i18n.S
import java.awt.Dimension

/**
 * When false, the preview falls back to an in-composition dialog instead of
 * opening a separate OS window. Headless UI tests use this escape hatch: the
 * test root cannot see into a second window's composition.
 */
internal val LocalImagePreviewWindowed = staticCompositionLocalOf { true }

/**
 * Hosts the viewer in a separate, normally-decorated OS window so the chat
 * stays visible behind it. The window is closed through its own controls —
 * the native close button or Escape — not by tapping the image. Mouse-wheel
 * zoom is handled by the shared viewer content.
 */
@Composable
internal actual fun ImagePreviewContainer(
    onDismiss: () -> Unit,
    title: String?,
    content: @Composable (dismissOnTap: Boolean) -> Unit,
) {
    if (!LocalImagePreviewWindowed.current) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            content(true)
        }
        return
    }
    Window(
        onCloseRequest = onDismiss,
        title = title ?: S.imagePreviewTitle,
        icon = desktopAppIconPainter(),
        state =
            rememberWindowState(
                position = WindowPosition(Alignment.Center),
                width = 1000.dp,
                height = 780.dp,
            ),
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        window.minimumSize = Dimension(420, 320)
        content(false)
    }
}

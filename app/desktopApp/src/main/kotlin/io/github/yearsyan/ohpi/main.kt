package io.github.yearsyan.ohpi

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 840.dp)
    val appIcon = desktopAppIconPainter()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pi",
        icon = appIcon,
        state = windowState,
        undecorated = true,
    ) {
        window.minimumSize = java.awt.Dimension(760, 520)
        DisposableEffect(window) {
            WindowsWindowFrame.applyTo(window)
            onDispose { }
        }
        App(
            titleBar = {
                DesktopTitleBar(
                    isMaximized = windowState.placement == WindowPlacement.Maximized,
                    onMinimize = { window.isMinimized = true },
                    onToggleMaximize = {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Maximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                    },
                    onClose = ::exitApplication,
                )
            },
        )
    }
}

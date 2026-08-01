package io.github.yearsyan.pi

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 840.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pi",
        state = windowState,
    ) {
        window.minimumSize = java.awt.Dimension(760, 520)
        App()
    }
}

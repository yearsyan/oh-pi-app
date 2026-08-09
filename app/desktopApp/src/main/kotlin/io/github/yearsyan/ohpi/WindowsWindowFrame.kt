package io.github.yearsyan.ohpi

import androidx.compose.ui.awt.ComposeWindow
import io.github.yearsyan.ohpi.ssh.applyWindowsNativeWindowFrame

/** Enables the native borderless DWM frame without routing Win32 messages through JNA. */
internal object WindowsWindowFrame {
    fun applyTo(window: ComposeWindow) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        runCatching {
            check(applyWindowsNativeWindowFrame(window.windowHandle)) {
                "native window-frame setup failed"
            }
        }.onFailure { error ->
            System.err.println("Could not enable the Windows-managed window frame: ${error.message}")
        }
    }
}

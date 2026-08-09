package io.github.yearsyan.ohpi

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** Enables DWM rounded corners and shadow without replacing Compose/AWT's native window procedure. */
internal object WindowsWindowFrame {
    private const val DWMWA_NCRENDERING_POLICY = 2
    private const val DWMNCRP_ENABLED = 2
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    fun applyTo(window: ComposeWindow) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        runCatching {
            val handle = window.windowHandle
            val hwnd = Pointer(handle)

            DwmApi.instance.setIntAttribute(hwnd, DWMWA_NCRENDERING_POLICY, DWMNCRP_ENABLED)
            DwmApi.instance.setIntAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
            DwmApi.instance.DwmExtendFrameIntoClientArea(hwnd, FrameMargins.onePixel())
        }.onFailure { error ->
            System.err.println("Could not enable the Windows-managed window frame: ${error.message}")
        }
    }

    private fun DwmApi.setIntAttribute(
        hwnd: Pointer,
        attribute: Int,
        value: Int,
    ) {
        val nativeValue = IntByReference(value)
        DwmSetWindowAttribute(hwnd, attribute, nativeValue.pointer, Int.SIZE_BYTES)
    }

    private interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: Pointer,
            attribute: Int,
            value: Pointer,
            valueSize: Int,
        ): Int

        fun DwmExtendFrameIntoClientArea(
            hwnd: Pointer,
            margins: FrameMargins,
        ): Int

        companion object {
            val instance: DwmApi =
                Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    @Structure.FieldOrder(
        "leftWidth",
        "rightWidth",
        "topHeight",
        "bottomHeight",
    )
    private class FrameMargins : Structure() {
        @JvmField var leftWidth: Int = 0
        @JvmField var rightWidth: Int = 0
        @JvmField var topHeight: Int = 0
        @JvmField var bottomHeight: Int = 0

        companion object {
            fun onePixel() =
                FrameMargins().apply {
                    leftWidth = 1
                    rightWidth = 1
                    topHeight = 1
                    bottomHeight = 1
                }
        }
    }
}

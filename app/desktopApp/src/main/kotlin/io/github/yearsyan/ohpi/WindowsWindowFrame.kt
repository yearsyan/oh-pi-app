package io.github.yearsyan.ohpi

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.ConcurrentHashMap

/** Lets Windows draw the native resize frame, rounded corners, and drop shadow. */
internal object WindowsWindowFrame {
    private const val GWL_STYLE = -16
    private const val GWLP_WNDPROC = -4

    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_SYSMENU = 0x00080000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private const val DWMWA_NCRENDERING_POLICY = 2
    private const val DWMNCRP_ENABLED = 2
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    private const val WM_STYLECHANGING = 0x007C
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCDESTROY = 0x0082

    private const val HTCLIENT = 1
    private const val HTCAPTION = 2
    private const val HTLEFT = 10
    private const val HTRIGHT = 11
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val HTBOTTOM = 15
    private const val HTBOTTOMLEFT = 16
    private const val HTBOTTOMRIGHT = 17

    private const val SM_CXSIZEFRAME = 32
    private const val SM_CYSIZEFRAME = 33
    private const val SM_CXPADDEDBORDER = 92

    private val windowProcedures = ConcurrentHashMap<Long, FrameWindowProcedure>()

    fun applyTo(window: ComposeWindow) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        runCatching {
            val handle = window.windowHandle
            val hwnd = Pointer(handle)
            installWindowProcedure(handle, hwnd)

            val currentStyle = User32Api.instance.GetWindowLongW(hwnd, GWL_STYLE)
            User32Api.instance.SetWindowLongW(hwnd, GWL_STYLE, managedFrameStyle(currentStyle))
            User32Api.instance.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                SWP_NOSIZE or
                    SWP_NOMOVE or
                    SWP_NOZORDER or
                    SWP_NOACTIVATE or
                    SWP_FRAMECHANGED,
            )

            DwmApi.instance.setIntAttribute(hwnd, DWMWA_NCRENDERING_POLICY, DWMNCRP_ENABLED)
            DwmApi.instance.setIntAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
            DwmApi.instance.DwmExtendFrameIntoClientArea(hwnd, FrameMargins.onePixel())
        }.onFailure { error ->
            System.err.println("Could not enable the Windows-managed window frame: ${error.message}")
        }
    }

    private fun installWindowProcedure(
        handle: Long,
        hwnd: Pointer,
    ) {
        if (windowProcedures.containsKey(handle)) return

        val procedure = FrameWindowProcedure(handle, hwnd)
        procedure.install()
        windowProcedures[handle] = procedure
    }

    private fun managedFrameStyle(style: Int): Int =
        (style and WS_CAPTION.inv()) or
            WS_THICKFRAME or
            WS_SYSMENU or
            WS_MINIMIZEBOX or
            WS_MAXIMIZEBOX

    private fun DwmApi.setIntAttribute(
        hwnd: Pointer,
        attribute: Int,
        value: Int,
    ) {
        val nativeValue = IntByReference(value)
        DwmSetWindowAttribute(hwnd, attribute, nativeValue.pointer, Int.SIZE_BYTES)
    }

    private interface User32Api : StdCallLibrary {
        fun GetWindowLongW(
            hwnd: Pointer,
            index: Int,
        ): Int

        fun SetWindowLongW(
            hwnd: Pointer,
            index: Int,
            newValue: Int,
        ): Int

        fun SetWindowLongPtrW(
            hwnd: Pointer,
            index: Int,
            newValue: Pointer,
        ): Pointer?

        fun CallWindowProcW(
            previousProcedure: Pointer,
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer?

        fun DefWindowProcW(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer?

        fun GetWindowRect(
            hwnd: Pointer,
            rect: WindowRect,
        ): Boolean

        fun GetDpiForWindow(hwnd: Pointer): Int

        fun GetSystemMetricsForDpi(
            index: Int,
            dpi: Int,
        ): Int

        fun IsZoomed(hwnd: Pointer): Boolean

        fun SetWindowPos(
            hwnd: Pointer,
            insertAfter: Pointer?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int,
        ): Boolean

        companion object {
            val instance: User32Api =
                Native.load("user32", User32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private fun interface NativeWindowProcedure : StdCallLibrary.StdCallCallback {
        fun invoke(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer?
    }

    private class FrameWindowProcedure(
        private val handle: Long,
        private val hwnd: Pointer,
    ) : NativeWindowProcedure {
        private var previousProcedure: Pointer? = null

        fun install() {
            previousProcedure =
                User32Api.instance.SetWindowLongPtrW(
                    hwnd,
                    GWLP_WNDPROC,
                    CallbackReference.getFunctionPointer(this),
                ) ?: error("SetWindowLongPtrW did not return the previous window procedure")
        }

        override fun invoke(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer? =
            when (message) {
                WM_STYLECHANGING -> handleStyleChanging(hwnd, message, wParam, lParam)
                WM_NCCALCSIZE -> handleNcCalcSize(hwnd, message, wParam, lParam)
                WM_NCHITTEST -> handleNcHitTest(hwnd, message, wParam, lParam)
                WM_NCDESTROY -> {
                    val result = callPrevious(hwnd, message, wParam, lParam)
                    windowProcedures.remove(handle, this)
                    result
                }
                else -> callPrevious(hwnd, message, wParam, lParam)
            }

        private fun handleStyleChanging(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer? {
            if (pointerValue(wParam).toInt() == GWL_STYLE && lParam != null) {
                val requestedStyle = lParam.getInt(Int.SIZE_BYTES.toLong())
                lParam.setInt(Int.SIZE_BYTES.toLong(), managedFrameStyle(requestedStyle))
            }
            return callPrevious(hwnd, message, wParam, lParam)
        }

        private fun handleNcCalcSize(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer? {
            if (lParam == null) return callPrevious(hwnd, message, wParam, lParam)

            val rectOffsets = LongArray(4) { index -> (index * Int.SIZE_BYTES).toLong() }
            val proposedWindowRect = rectOffsets.map(lParam::getInt)
            val result = callPrevious(hwnd, message, wParam, lParam)

            if (!User32Api.instance.IsZoomed(hwnd)) {
                // Let Compose paint all the way to every window edge. Retaining the
                // native left/right insets leaves visible gutters and pushes the
                // custom caption buttons away from the corners. WS_THICKFRAME plus
                // WM_NCHITTEST still provides native-sized resize targets, while DWM
                // continues to clip the client surface and draw the outer frame.
                rectOffsets.forEachIndexed { index, offset ->
                    lParam.setInt(offset, proposedWindowRect[index])
                }
                return null
            }
            return result
        }

        private fun handleNcHitTest(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer? {
            val defaultResult = callPrevious(hwnd, message, wParam, lParam)
            if (User32Api.instance.IsZoomed(hwnd) || lParam == null) return defaultResult

            resizeHitTest(hwnd, lParam)?.let { return Pointer.createConstant(it.toLong()) }

            // The title area is client content. WindowDraggableArea handles moving it,
            // while the custom Compose controls remain clickable.
            return if (pointerValue(defaultResult).toInt() == HTCAPTION) {
                Pointer.createConstant(HTCLIENT.toLong())
            } else {
                defaultResult
            }
        }

        private fun resizeHitTest(
            hwnd: Pointer,
            lParam: Pointer,
        ): Int? {
            val rect = WindowRect()
            if (!User32Api.instance.GetWindowRect(hwnd, rect)) return null
            rect.read()

            val packedPoint = pointerValue(lParam)
            val x = (packedPoint and 0xFFFF).toShort().toInt()
            val y = ((packedPoint ushr 16) and 0xFFFF).toShort().toInt()
            val dpi = User32Api.instance.GetDpiForWindow(hwnd).takeIf { it > 0 } ?: 96
            val horizontalBorder =
                (
                    User32Api.instance.GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi) +
                        User32Api.instance.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
                ).coerceAtLeast(1)
            val verticalBorder =
                (
                    User32Api.instance.GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi) +
                        User32Api.instance.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
                ).coerceAtLeast(1)

            val onLeft = x < rect.left + horizontalBorder
            val onRight = x >= rect.right - horizontalBorder
            val onTop = y < rect.top + verticalBorder
            val onBottom = y >= rect.bottom - verticalBorder

            return when {
                onTop && onLeft -> HTTOPLEFT
                onTop && onRight -> HTTOPRIGHT
                onBottom && onLeft -> HTBOTTOMLEFT
                onBottom && onRight -> HTBOTTOMRIGHT
                onLeft -> HTLEFT
                onRight -> HTRIGHT
                onTop -> HTTOP
                onBottom -> HTBOTTOM
                else -> null
            }
        }

        private fun callPrevious(
            hwnd: Pointer,
            message: Int,
            wParam: Pointer?,
            lParam: Pointer?,
        ): Pointer? =
            previousProcedure?.let { previous ->
                User32Api.instance.CallWindowProcW(previous, hwnd, message, wParam, lParam)
            } ?: User32Api.instance.DefWindowProcW(hwnd, message, wParam, lParam)
    }

    private fun pointerValue(pointer: Pointer?): Long =
        pointer?.let(Pointer::nativeValue) ?: 0L

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
    internal class FrameMargins : Structure() {
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

    @Structure.FieldOrder(
        "left",
        "top",
        "right",
        "bottom",
    )
    internal class WindowRect : Structure() {
        @JvmField var left: Int = 0
        @JvmField var top: Int = 0
        @JvmField var right: Int = 0
        @JvmField var bottom: Int = 0
    }
}

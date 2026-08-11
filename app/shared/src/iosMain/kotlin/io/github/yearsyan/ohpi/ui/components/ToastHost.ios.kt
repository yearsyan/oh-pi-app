@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.yearsyan.ohpi.chat.Toast
import io.github.yearsyan.ohpi.liquidglass.OhPiNativeToastDismiss
import io.github.yearsyan.ohpi.liquidglass.OhPiNativeToastShow

/** Presents toast feedback in a UIKit overlay window above native interop views. */
@Composable
internal actual fun PlatformToastHost(toasts: List<Toast>, darkTheme: Boolean) {
    val toast = toasts.lastOrNull()

    LaunchedEffect(toast?.id, darkTheme) {
        if (toast == null) {
            OhPiNativeToastDismiss()
        } else {
            OhPiNativeToastShow(
                toast.text,
                when (toast.kind) {
                    Toast.Kind.Info -> NativeToastKindInfo
                    Toast.Kind.Error -> NativeToastKindError
                    Toast.Kind.Success -> NativeToastKindSuccess
                },
                darkTheme,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { OhPiNativeToastDismiss() }
    }
}

private const val NativeToastKindInfo = 0
private const val NativeToastKindError = 1
private const val NativeToastKindSuccess = 2

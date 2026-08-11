package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import io.github.yearsyan.ohpi.chat.Toast

@Composable
internal actual fun PlatformToastHost(toasts: List<Toast>, darkTheme: Boolean) {
    ComposeToastHost(toasts)
}

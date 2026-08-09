package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable

@Composable
internal actual fun AppContextMenu(
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    content()
}

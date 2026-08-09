package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    LegacyScrollToBottomButton(visible = visible, onClick = onClick, modifier = modifier)
}

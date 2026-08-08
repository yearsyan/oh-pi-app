package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformComposerSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    LegacyComposerSurface(modifier = modifier, content = content)
}

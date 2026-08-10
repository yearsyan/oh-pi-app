package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

@Composable
internal actual fun PlatformComposerSurface(
    backdropState: HazeState?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (platformSupportsComposerBackdropBlur && backdropState != null) {
        BlurredComposerSurface(
            backdropState = backdropState,
            modifier = modifier,
            content = content,
        )
    } else {
        LegacyComposerSurface(modifier = modifier, content = content)
    }
}

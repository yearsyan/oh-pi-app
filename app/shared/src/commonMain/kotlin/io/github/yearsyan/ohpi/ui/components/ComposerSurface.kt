package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Platform boundary for the message composer's outer visual surface. */
@Composable
internal expect fun PlatformComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)

/** Existing Material surface retained on Android, desktop, and pre-iOS 26. */
@Composable
internal fun LegacyComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // In dark mode surface == background, so a shadow-only card disappears:
    // trade the shadow for a visible bright outline there.
    val darkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = if (darkScheme) 0.dp else 4.dp,
        border = if (darkScheme) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        content = content,
    )
}

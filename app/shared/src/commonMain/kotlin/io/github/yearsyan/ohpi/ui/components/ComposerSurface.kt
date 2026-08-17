package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect

/** Whether this target should capture chat content for the composer's backdrop. */
internal expect val platformSupportsComposerBackdropBlur: Boolean

/** Platform boundary for the message composer's outer visual surface. */
@Composable
internal expect fun PlatformComposerSurface(
    backdropState: HazeState?,
    modifier: Modifier = Modifier,
    glassAlpha: Float = 1f,
    content: @Composable () -> Unit,
)

/** Existing Material surface retained for unsupported platforms and effect fallbacks. */
@Composable
internal fun LegacyComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val darkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(if (darkScheme) 1.dp else 0.5.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

/** GPU-backed background blur used by desktop and Android 12+. */
@Composable
internal fun BlurredComposerSurface(
    backdropState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val darkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(26.dp)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val border =
        BorderStroke(
            if (darkScheme) 1.dp else 0.5.dp,
            MaterialTheme.colorScheme.outline.copy(
                alpha = if (darkScheme) 0.55f else 0.4f,
            ),
        )

    Surface(
        modifier =
            modifier
                // The effect layer sits outside Surface's own clip, so clip it
                // explicitly to prevent rectangular haze around rounded corners.
                .clip(shape)
                .hazeEffect(state = backdropState) {
                    // Render one quarter of the source pixels. The 16dp Gaussian
                    // radius masks the quality loss while reducing the offscreen work.
                    inputScale = HazeInputScale.Fixed(0.5f)
                    expandLayerBounds = true
                    blurEffect {
                        blurRadius = 16.dp
                        noiseFactor = 0f
                        backgroundColor = surfaceColor
                        colorEffects =
                            listOf(
                                HazeColorEffect.tint(
                                    surfaceColor.copy(alpha = if (darkScheme) 0.68f else 0.72f),
                                ),
                            )
                        fallbackTint = HazeColorEffect.tint(surfaceColor)
                        blurredEdgeTreatment = BlurredEdgeTreatment(shape)
                    }
                },
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = border,
        content = content,
    )
}

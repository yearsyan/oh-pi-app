package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.i18n.S

/** Platform boundary for the floating jump-to-bottom affordance. */
@Composable
internal expect fun PlatformScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glassAlpha: Float = 1f,
)

/** Existing Material button retained on Android, desktop, and pre-iOS 26. */
@Composable
internal fun LegacyScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.size(38.dp),
        enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f, animationSpec = tween(150)),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = S.scrollToBottom,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

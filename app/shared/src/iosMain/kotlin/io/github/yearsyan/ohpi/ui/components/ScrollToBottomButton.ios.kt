@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.liquidglass.OhPiLiquidGlassCreateScrollButtonView
import io.github.yearsyan.ohpi.liquidglass.OhPiLiquidGlassIsAvailable
import io.github.yearsyan.ohpi.liquidglass.OhPiLiquidGlassSetScrollButtonProgress
import io.github.yearsyan.ohpi.liquidglass.OhPiLiquidGlassUpdateScrollButton
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIColor
import platform.darwin.NSObject

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    glassAlpha: Float,
) {
    if (!isScrollButtonLiquidGlassAvailable()) {
        LegacyScrollToBottomButton(visible = visible, onClick = onClick, modifier = modifier)
        return
    }

    val darkAppearance = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    val latestOnClick by rememberUpdatedState(onClick)
    val accessibilityLabel = S.scrollToBottom

    // Overlay interop views ignore the caller's AnimatedVisibility, so the
    // legacy fade/scale is reproduced on the native view itself. glassAlpha
    // replays navigation fades that the Compose scene cannot apply to it.
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        progress.animateTo(if (visible) 1f else 0f, animationSpec = tween(150))
    }
    val effectiveProgress = progress.value * glassAlpha

    UIKitView(
        factory = {
            val tapTarget = TapTarget { latestOnClick() }
            val view =
                checkNotNull(
                    OhPiLiquidGlassCreateScrollButtonView(
                        tapTarget,
                        NSSelectorFromString("handleTap"),
                    ),
                ) {
                    "Liquid Glass bridge returned no scroll button on a supported OS"
                }
            OhPiLiquidGlassUpdateScrollButton(
                view,
                darkAppearance,
                iconTint.toUIColor(),
                accessibilityLabel,
            )
            OhPiLiquidGlassSetScrollButtonProgress(view, effectiveProgress.toDouble())
            view
        },
        update = { view ->
            OhPiLiquidGlassUpdateScrollButton(
                view,
                darkAppearance,
                iconTint.toUIColor(),
                accessibilityLabel,
            )
            OhPiLiquidGlassSetScrollButtonProgress(view, effectiveProgress.toDouble())
        },
        modifier = modifier.size(38.dp),
        properties =
            UIKitInteropProperties(
                // Embedded interop cuts through the Compose canvas and exposes
                // the white root view; only overlay mode blends correctly.
                placedAsOverlay = true,
                // Claims touches exclusively so a tap cannot also reach the
                // message row underneath, unlike the composer this view is
                // small enough that losing scroll-through-drag is fine.
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
            ),
    )
}

private class TapTarget(
    private val action: () -> Unit,
) : NSObject() {
    @ObjCAction
    fun handleTap() {
        action()
    }
}

private fun Color.toUIColor(): UIColor =
    UIColor.colorWithRed(
        red = red.toDouble(),
        green = green.toDouble(),
        blue = blue.toDouble(),
        alpha = alpha.toDouble(),
    )

private fun isScrollButtonLiquidGlassAvailable(): Boolean = OhPiLiquidGlassIsAvailable()

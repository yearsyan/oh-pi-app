@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIView
import dev.chrisbanes.haze.HazeState
import io.github.yearsyan.ohpi.i18n.LocalStrings
import io.github.yearsyan.ohpi.theme.LocalPiExtras
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSClassFromString
import platform.UIKit.UICornerConfiguration
import platform.UIKit.UICornerRadius
import platform.UIKit.UIGlassEffect
import platform.UIKit.UIGlassEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIVisualEffectView

@Composable
internal actual fun PlatformComposerSurface(
    backdropState: HazeState?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (!isLiquidGlassAvailable()) {
        LegacyComposerSurface(modifier = modifier, content = content)
        return
    }

    var contentHeight by remember { mutableStateOf(ComposerMinimumHeight) }
    val darkAppearance = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val latestContent by rememberUpdatedState(content)
    val latestColorScheme by rememberUpdatedState(MaterialTheme.colorScheme)
    val latestTypography by rememberUpdatedState(MaterialTheme.typography)
    val latestShapes by rememberUpdatedState(MaterialTheme.shapes)
    val latestStrings by rememberUpdatedState(LocalStrings.current)
    val latestExtras by rememberUpdatedState(LocalPiExtras.current)
    val latestDensity by rememberUpdatedState(LocalDensity.current)
    val latestLayoutDirection by rememberUpdatedState(LocalLayoutDirection.current)
    val latestUriHandler by rememberUpdatedState(LocalUriHandler.current)

    UIKitView(
        factory = {
            val composeContent =
                ComposeUIView(
                    configure = {
                        opaque = false
                        onFocusBehavior = OnFocusBehavior.DoNothing
                    },
                ) {
                    CompositionLocalProvider(
                        LocalStrings provides latestStrings,
                        LocalPiExtras provides latestExtras,
                        LocalDensity provides latestDensity,
                        LocalLayoutDirection provides latestLayoutDirection,
                        LocalUriHandler provides latestUriHandler,
                    ) {
                        MaterialTheme(
                            colorScheme = latestColorScheme,
                            typography = latestTypography,
                            shapes = latestShapes,
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides latestColorScheme.onSurface,
                            ) {
                                MeasuredComposerContent(
                                    onHeightChanged = { measuredHeight ->
                                        val nextHeight =
                                            measuredHeight.coerceIn(
                                                ComposerMinimumHeight,
                                                ComposerMeasurementMaxHeight,
                                            )
                                        if (contentHeight != nextHeight) contentHeight = nextHeight
                                    },
                                ) {
                                    latestContent()
                                }
                            }
                        }
                    }
                }
            LiquidGlassComposerView(composeContent).apply {
                updateAppearance(darkAppearance)
            }
        },
        update = { view -> view.updateAppearance(darkAppearance) },
        // ComposeUIView has no Auto Layout intrinsic height. Keep UIKitView on
        // an explicit Compose-owned height so its first measurement cannot be 0.
        modifier = modifier.height(contentHeight),
        properties =
            UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
                placedAsOverlay = true,
            ),
    )
}

/**
 * Measures the actual composer with loose vertical constraints while keeping
 * the native ComposeUIView itself at the current visible height. This allows
 * image rows, queued prompts, and slash suggestions to grow or shrink the card.
 */
@Composable
private fun MeasuredComposerContent(
    onHeightChanged: (Dp) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Layout(
        content = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        onHeightChanged(with(density) { size.height.toDp() })
                    },
            ) {
                content()
            }
        },
    ) { measurables, constraints ->
        val measurementMaxHeight = with(density) { ComposerMeasurementMaxHeight.roundToPx() }
        val placeable =
            measurables.single().measure(
                constraints.copy(
                    minHeight = 0,
                    maxHeight = measurementMaxHeight,
                ),
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(0, 0)
        }
    }
}

/**
 * Keeps native glass behind a separately rendered Compose subtree. The text
 * field and buttons therefore remain crisp instead of being sampled by glass.
 */
private class LiquidGlassComposerView(
    private val composeContent: UIView,
) : UIView(frame = CGRectZero.readValue()) {
    private val glassView =
        UIVisualEffectView(
            effect =
                UIGlassEffect.effectWithStyle(UIGlassEffectStyle.UIGlassEffectStyleRegular).apply {
                    interactive = false
                },
        )

    init {
        opaque = false
        backgroundColor = UIColor.clearColor
        clipsToBounds = true

        val corners =
            UICornerConfiguration.configurationWithRadius(
                UICornerRadius.fixedRadius(ComposerCornerRadius),
            )
        cornerConfiguration = corners
        glassView.cornerConfiguration = corners
        glassView.clipsToBounds = true
        glassView.userInteractionEnabled = true
        composeContent.opaque = false
        composeContent.backgroundColor = UIColor.clearColor

        addSubview(glassView)
        glassView.contentView.addSubview(composeContent)
    }

    /** Keeps UIKit glass in sync with the app theme, including forced themes. */
    fun updateAppearance(isDark: Boolean) {
        val interfaceStyle =
            if (isDark) {
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
            } else {
                UIUserInterfaceStyle.UIUserInterfaceStyleLight
            }
        if (overrideUserInterfaceStyle != interfaceStyle) {
            overrideUserInterfaceStyle = interfaceStyle
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        glassView.setFrame(bounds)
        composeContent.setFrame(glassView.contentView.bounds)
    }
}

private fun isLiquidGlassAvailable(): Boolean = NSClassFromString("UIGlassEffect") != null

private val ComposerMinimumHeight = 116.dp
private val ComposerMeasurementMaxHeight = 896.dp
private const val ComposerCornerRadius = 26.0

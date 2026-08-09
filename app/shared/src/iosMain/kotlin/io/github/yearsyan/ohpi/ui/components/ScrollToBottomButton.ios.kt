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
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSClassFromString
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIColor
import platform.UIKit.UICornerConfiguration
import platform.UIKit.UICornerRadius
import platform.UIKit.UIGlassEffect
import platform.UIKit.UIGlassEffectStyle
import platform.UIKit.UIImage
import platform.UIKit.UIImageSymbolConfiguration
import platform.UIKit.UIImageSymbolWeightSemibold
import platform.UIKit.UIImageView
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIVisualEffectView
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityTraits
import platform.UIKit.isAccessibilityElement
import platform.darwin.NSObject

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
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
    // legacy fade/scale is reproduced on the native view itself.
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        progress.animateTo(if (visible) 1f else 0f, animationSpec = tween(150))
    }

    UIKitView(
        factory = {
            LiquidGlassScrollButtonView().apply {
                onTap = { latestOnClick() }
                updateAppearance(darkAppearance, iconTint, accessibilityLabel)
                updateVisibility(progress.value)
            }
        },
        update = { view ->
            view.onTap = { latestOnClick() }
            view.updateAppearance(darkAppearance, iconTint, accessibilityLabel)
            view.updateVisibility(progress.value)
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

/**
 * Native circular glass button. The SF Symbol chevron sits inside the glass
 * content view so it stays crisp; its tint is pushed from the Compose theme
 * so it matches the legacy Material icon color exactly.
 */
private class LiquidGlassScrollButtonView : UIView(frame = CGRectZero.readValue()) {
    var onTap: (() -> Unit)? = null

    private val tapTarget = TapTarget { onTap?.invoke() }

    private val glassView =
        UIVisualEffectView(
            effect =
                UIGlassEffect.effectWithStyle(UIGlassEffectStyle.UIGlassEffectStyleRegular).apply {
                    // Brighten on touch-down; this is a button, not a static card.
                    interactive = true
                },
        )

    private val iconView =
        UIImageView(
            image =
                UIImage.systemImageNamed(
                    "chevron.down",
                    withConfiguration =
                        UIImageSymbolConfiguration.configurationWithPointSize(
                            pointSize = ScrollButtonSymbolPointSize,
                            weight = UIImageSymbolWeightSemibold,
                        ),
                ),
        )

    init {
        opaque = false
        backgroundColor = UIColor.clearColor
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton

        val corners =
            UICornerConfiguration.configurationWithRadius(
                UICornerRadius.fixedRadius(ScrollButtonCornerRadius),
            )
        cornerConfiguration = corners
        glassView.cornerConfiguration = corners
        glassView.clipsToBounds = true

        iconView.contentMode = UIViewContentMode.UIViewContentModeCenter

        addSubview(glassView)
        glassView.contentView.addSubview(iconView)

        addGestureRecognizer(
            UITapGestureRecognizer(
                target = tapTarget,
                action = NSSelectorFromString("handleTap"),
            ),
        )
    }

    /** Keeps UIKit glass in sync with the app theme, including forced themes. */
    fun updateAppearance(
        isDark: Boolean,
        tint: Color,
        label: String,
    ) {
        val interfaceStyle =
            if (isDark) {
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
            } else {
                UIUserInterfaceStyle.UIUserInterfaceStyleLight
            }
        if (overrideUserInterfaceStyle != interfaceStyle) {
            overrideUserInterfaceStyle = interfaceStyle
        }
        iconView.tintColor = tint.toUIColor()
        if (accessibilityLabel != label) {
            accessibilityLabel = label
        }
    }

    /** Mirrors the legacy fade/scale and drops hit-testing while invisible. */
    fun updateVisibility(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        alpha = clamped.toDouble()
        val scale = (0.85 + 0.15 * clamped).toDouble()
        transform = CGAffineTransformMakeScale(scale, scale)
        val shouldHide = clamped <= 0.01f
        if (hidden != shouldHide) {
            hidden = shouldHide
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        glassView.setFrame(bounds)
        iconView.setFrame(glassView.contentView.bounds)
    }
}

private fun Color.toUIColor(): UIColor =
    UIColor.colorWithRed(
        red = red.toDouble(),
        green = green.toDouble(),
        blue = blue.toDouble(),
        alpha = alpha.toDouble(),
    )

private fun isScrollButtonLiquidGlassAvailable(): Boolean =
    NSClassFromString("UIGlassEffect") != null

/** The button is fixed at 38.dp, so a 19pt radius keeps it circular. */
private const val ScrollButtonCornerRadius = 19.0

private const val ScrollButtonSymbolPointSize = 15.0

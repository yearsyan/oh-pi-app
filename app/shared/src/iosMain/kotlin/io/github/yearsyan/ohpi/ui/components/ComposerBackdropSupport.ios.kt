@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.components

import io.github.yearsyan.ohpi.liquidglass.OhPiLiquidGlassIsAvailable

internal actual val platformSupportsComposerBackdropBlur: Boolean = false

/** Only real Liquid Glass interop views need the manual exit fade. */
internal actual val platformNeedsGlassExitFade: Boolean
    get() = OhPiLiquidGlassIsAvailable()

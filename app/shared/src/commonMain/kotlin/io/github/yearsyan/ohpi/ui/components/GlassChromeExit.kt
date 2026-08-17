package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * True on platforms where native Liquid Glass chrome is rendered as interop
 * UIViews that Compose scene transitions cannot fade.
 */
internal expect val platformNeedsGlassExitFade: Boolean

/**
 * Alpha applied to native glass chrome (composer, scroll-to-bottom button).
 * Overlay interop views ignore the alpha of the Compose scene, so navigation
 * fades must be replayed on the native views through this local.
 */
internal val LocalGlassChromeAlpha = compositionLocalOf { 1f }

/**
 * Fades native glass chrome in sync with the exit of the owning destination,
 * covering both imperative pops (fade first, then pop) and interactive edge
 * swipes (alpha tracks the gesture progress).
 */
internal class GlassExitController internal constructor(
    private val alphaState: MutableFloatState,
    private val scope: CoroutineScope,
) {
    val glassAlpha: Float
        get() = alphaState.floatValue

    private var started = false
    private var animation: Job? = null

    /** Runs [action] after a short glass fade, or immediately without glass. */
    fun fadeOutThen(action: () -> Unit) {
        if (started) return
        started = true
        if (!platformNeedsGlassExitFade) {
            action()
            return
        }
        animateAlphaTo(0f, GlassExitFadeMillis) { action() }
    }

    /** Interactive edge swipe: the glass tracks the gesture progress directly. */
    fun syncGestureProgress(progress: Float) {
        if (started) return
        animation?.cancel()
        alphaState.floatValue = 1f - progress.coerceIn(0f, 1f)
    }

    /** Swipe finished and the pop is proceeding: complete the fade alongside it. */
    fun fadeOutRemaining() {
        if (started) return
        started = true
        animateAlphaTo(0f, GlassGestureFinishMillis) {}
    }

    /** Swipe cancelled before the pop committed: restore full opacity. */
    fun restore() {
        if (started) return
        animateAlphaTo(1f, GlassGestureRestoreMillis) {}
    }

    private fun animateAlphaTo(target: Float, durationMillis: Int, onEnd: () -> Unit) {
        animation?.cancel()
        animation =
            scope.launch {
                animate(
                    initialValue = alphaState.floatValue,
                    targetValue = target,
                    animationSpec =
                        tween(durationMillis, easing = FastOutLinearInEasing),
                ) { value, _ ->
                    alphaState.floatValue = value
                }
                onEnd()
            }
    }
}

/** Keeps one controller per destination so the exit fade runs at most once. */
@Composable
internal fun rememberGlassExitController(): GlassExitController {
    val alphaState = remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    return remember { GlassExitController(alphaState, scope) }
}

/**
 * On iOS, passively observes the system edge-swipe back gesture so glass
 * chrome can track it; a no-op elsewhere. [isActive] reports whether the
 * owning destination is still the current one, distinguishing a committed pop
 * from a cancelled swipe.
 */
@Composable
internal expect fun SyncGlassWithBackGesture(
    controller: GlassExitController,
    isActive: () -> Boolean,
)

/** Slightly shorter than the NavHost pop fade so the glass is gone first. */
private const val GlassExitFadeMillis = 180
private const val GlassGestureFinishMillis = 140
private const val GlassGestureRestoreMillis = 160

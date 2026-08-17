@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlin.math.abs
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateCancelled
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIGestureRecognizerStateFailed
import platform.UIKit.UIScreenEdgePanGestureRecognizer
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Compose Multiplatform handles iOS swipe-back with a UIScreenEdgePanGesture
 * recognizer feeding NavHost's own handler; there is no public observer API.
 * Adding another target to that recognizer is passive, so the glass chrome can
 * follow the gesture without disturbing the navigation handling.
 */
@Composable
internal actual fun SyncGlassWithBackGesture(
    controller: GlassExitController,
    isActive: () -> Boolean,
) {
    if (!platformNeedsGlassExitFade) return
    val viewController = LocalUIViewController.current
    val latestController by rememberUpdatedState(controller)
    val latestIsActive by rememberUpdatedState(isActive)

    DisposableEffect(viewController) {
        val target =
            BackGestureTarget { recognizer ->
                when (recognizer.state) {
                    UIGestureRecognizerStateBegan ->
                        latestController.syncGestureProgress(0f)
                    UIGestureRecognizerStateChanged ->
                        latestController.syncGestureProgress(recognizer.edgeSwipeProgress())
                    // CMP treats Failed like Ended (completed), so both need the
                    // committed-or-cancelled check below.
                    UIGestureRecognizerStateEnded, UIGestureRecognizerStateFailed ->
                        // NavHost can still cancel short or slow swipes. Check on
                        // the next runloop turn whether the pop committed.
                        dispatch_async(dispatch_get_main_queue()) {
                            if (latestIsActive()) {
                                latestController.restore()
                            } else {
                                latestController.fadeOutRemaining()
                            }
                        }
                    UIGestureRecognizerStateCancelled ->
                        latestController.restore()
                    else -> Unit
                }
            }

        var attached = emptyList<UIScreenEdgePanGestureRecognizer>()
        // CMP attaches its edge recognizers while creating the scene; retry
        // briefly in case this composition ran first.
        val attachScope = CoroutineScope(Job() + Dispatchers.Main)
        attachScope.launch {
            repeat(15) {
                val recognizers = edgeSwipeRecognizersFor(viewController.view)
                if (recognizers.isNotEmpty()) {
                    recognizers.forEach { it.addTarget(target, BackGestureTargetAction) }
                    attached = recognizers
                    return@launch
                }
                delay(200)
            }
        }

        onDispose {
            attachScope.cancel()
            attached.forEach { it.removeTarget(target, BackGestureTargetAction) }
        }
    }
}

private val BackGestureTargetAction = NSSelectorFromString("handleBackGesture:")

private class BackGestureTarget(
    private val onEvent: (UIScreenEdgePanGestureRecognizer) -> Unit,
) : NSObject() {
    @ObjCAction
    fun handleBackGesture(recognizer: UIScreenEdgePanGestureRecognizer) {
        onEvent(recognizer)
    }
}

/** Horizontal travel as a fraction of the view width, direction-agnostic. */
private fun UIScreenEdgePanGestureRecognizer.edgeSwipeProgress(): Float {
    val hostView = view ?: return 0f
    val width = hostView.bounds.useContents { size.width }
    if (width <= 0.0) return 0f
    val distance = translationInView(hostView).useContents { abs(x) }
    return (distance / width).toFloat().coerceIn(0f, 1f)
}

/**
 * CMP attaches its back-gesture recognizers to the topmost view below the
 * window (see UIKitNavigationEventInput.onDidMoveToWindow), which sits above
 * the Compose view controller's view in a SwiftUI host, so look there instead
 * of traversing downwards. Fall back to a full window scan just in case.
 */
private fun edgeSwipeRecognizersFor(view: UIView): List<UIScreenEdgePanGestureRecognizer> {
    val window = view.window ?: return emptyList()
    var host: UIView = view
    while (true) {
        val superview = host.superview ?: break
        if (superview == window) break
        host = superview
    }
    val direct =
        host.gestureRecognizers.orEmpty().mapNotNull { it as? UIScreenEdgePanGestureRecognizer }
    if (direct.isNotEmpty()) return direct
    val out = mutableListOf<UIScreenEdgePanGestureRecognizer>()
    collectEdgeSwipeRecognizers(window, out)
    return out
}

private fun collectEdgeSwipeRecognizers(
    view: UIView,
    out: MutableList<UIScreenEdgePanGestureRecognizer>,
) {
    view.gestureRecognizers?.forEach { recognizer ->
        (recognizer as? UIScreenEdgePanGestureRecognizer)?.let { out += it }
    }
    view.subviews.forEach { subview ->
        (subview as? UIView)?.let { collectEdgeSwipeRecognizers(it, out) }
    }
}

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val pacerBaseMark = TimeSource.Monotonic.markNow()

/** Remaining forward scroll when the final item is already measured. */
private fun LazyListState.visibleTailScrollDistance(lastIndex: Int): Int? {
    val info = layoutInfo
    val lastItem = info.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return null
    val contentEnd = info.viewportEndOffset - info.afterContentPadding
    return (lastItem.offset + lastItem.size - contentEnd).coerceAtLeast(0)
}

/** Remaining forward scroll to the content end, or `null` while the tail is not measured. */
internal fun LazyListState.remainingScrollToBottomPx(): Int? {
    val totalItemsCount = layoutInfo.totalItemsCount
    if (totalItemsCount == 0) return 0
    return visibleTailScrollDistance(totalItemsCount - 1)
}

/** Whether the measured tail is no farther than [thresholdPx] from the content end. */
internal fun LazyListState.isWithinBottomThreshold(thresholdPx: Int): Boolean =
    remainingScrollToBottomPx()?.let { it <= thresholdPx.coerceAtLeast(0) } == true

/**
 * Serializes tail-follow scrolls so at most one starts per [minInterval].
 * Callers wait out the remainder of the throttle window instead of being
 * dropped, so the final delta of a stream still lands at the bottom once the
 * window elapses — a plain skip would leave the tail short of the fold.
 *
 * [nowMs] is injectable so tests can run the pacer on a virtual clock.
 */
internal class FollowScrollPacer(
    private val minInterval: Duration = 100.milliseconds,
    private val nowMs: () -> Long = { pacerBaseMark.elapsedNow().inWholeMilliseconds },
) {
    private val mutex = Mutex()
    private var lastScrollAtMs: Long? = null

    /** Suspends until [minInterval] has passed since the previous granted turn. */
    suspend fun awaitTurn() {
        mutex.withLock {
            val last = lastScrollAtMs
            if (last != null) {
                val remainingMs = minInterval.inWholeMilliseconds - (nowMs() - last)
                if (remainingMs > 0) delay(remainingMs)
            }
            lastScrollAtMs = nowMs()
        }
    }
}

/**
 * Scrolls so the very end of the list content (including bottom content padding)
 * becomes visible.
 *
 * Plain `scrollToItem(index)` aligns the item's top edge with the viewport top.
 * When the last item is taller than the viewport — a long assistant run is one
 * grouped item and can easily be — that stops at the item's top and leaves its
 * tail below the fold. The second, measured offset reaches the actual tail
 * without using an overflow-prone unbounded offset.
 *
 * If the tail is already visible, scroll by only the remaining distance. This
 * avoids needlessly moving back to the item's top before returning to its end.
 */
internal suspend fun LazyListState.scrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return

    visibleTailScrollDistance(lastIndex)?.let { distance ->
        if (distance > 0) scrollBy(distance.toFloat())
        return
    }

    // First make the last item measurable without relying on an unbounded
    // offset. Android's lazy-list implementation can overflow or stop one item
    // early when Int.MAX_VALUE is used while the target is still off-screen.
    scrollToItem(lastIndex)
    val remainingOffset = visibleTailScrollDistance(lastIndex) ?: return
    if (remainingOffset > 0) {
        scrollToItem(lastIndex, scrollOffset = remainingOffset)
    }
}

/**
 * Animated variant of [scrollToBottom]. A visible tail uses one relative
 * animation straight to the bottom. An off-screen tail still uses bounded
 * targets: first bring it into layout, then animate through the measured
 * remainder when that grouped item is taller than the viewport.
 */
internal suspend fun LazyListState.animateScrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return

    visibleTailScrollDistance(lastIndex)?.let { distance ->
        if (distance > 0) animateScrollBy(distance.toFloat())
        return
    }

    animateScrollToItem(lastIndex)
    val remainingOffset = visibleTailScrollDistance(lastIndex) ?: return
    if (remainingOffset > 0) {
        animateScrollToItem(lastIndex, scrollOffset = remainingOffset)
    }
}

/**
 * Non-suspending variant of [scrollToBottom]: records the scroll request
 * synchronously so it is applied during the next layout pass. Prefer this in
 * `LaunchedEffect`s driven by recomposition (e.g. entering a session): a
 * suspending scroll launched from such an effect may never resume before the
 * composition goes idle, leaving the list stuck at the top.
 */
internal fun LazyListState.requestScrollToBottom(lastIndex: Int) {
    if (lastIndex >= 0) requestScrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
}

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
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
private const val BottomSeekViewportMultiplier = 3f
private const val BottomSeekHandoffFraction = 0.6f
private const val ScrollConsumptionTolerancePx = 0.5f

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
 * Queues a bounded correction for the measured gap below the visible tail.
 *
 * Bottom-anchored height animations call this once per layout step. The
 * correction advances from the current first-visible anchor by only the new
 * gap. This is safe while LazyColumn is completing a measure pass and avoids
 * repeatedly retargeting the growing final item with an extreme offset.
 * Returns `false` when the tail is not currently measured.
 */
internal fun LazyListState.compensateVisibleTailToBottom(): Boolean {
    val distance = remainingScrollToBottomPx() ?: return false
    if (distance > 0) {
        val targetOffset =
            (firstVisibleItemScrollOffset.toLong() + distance)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        requestScrollToItem(firstVisibleItemIndex, targetOffset)
    }
    return true
}

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
 * animation straight to the bottom. When the tail is still off-screen, short
 * forward animation segments bring it into layout and hand off before their
 * spring velocity reaches zero. Once measured, the same animation state keeps
 * its velocity while retargeting to the exact remaining distance.
 *
 * Keeping the whole seek inside one scroll mutation matters for a long final
 * assistant run: snapping to that grouped item's top and only animating its
 * measured remainder makes the earlier process blocks disappear instantly,
 * while two independent animations visibly stop between the same two phases.
 */
internal suspend fun LazyListState.animateScrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return

    visibleTailScrollDistance(lastIndex)?.let { distance ->
        if (distance > 0) animateScrollBy(distance.toFloat())
        return
    }

    scroll {
        var animationState = AnimationState(initialValue = 0f)
        var tailMeasured = false
        var reachedContentEnd = false

        while (!tailMeasured && !reachedContentEnd) {
            val viewportSize =
                (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                    .coerceAtLeast(1)
            val segmentTarget = viewportSize * BottomSeekViewportMultiplier
            val handoffAt = segmentTarget * BottomSeekHandoffFraction
            var consumedInSegment = 0f

            animationState = animationState.copy(value = 0f)
            animationState.animateTo(
                targetValue = segmentTarget,
                sequentialAnimation = animationState.velocity != 0f,
            ) {
                val requested = value - consumedInSegment
                if (requested > 0f) {
                    val consumed = scrollBy(requested)
                    consumedInSegment += consumed
                    tailMeasured = visibleTailScrollDistance(lastIndex) != null
                    reachedContentEnd = consumed < requested - ScrollConsumptionTolerancePx
                }

                if (tailMeasured || reachedContentEnd || value >= handoffAt) {
                    cancelAnimation()
                }
            }
        }

        val remainingOffset = visibleTailScrollDistance(lastIndex) ?: return@scroll
        if (remainingOffset <= 0) return@scroll

        var consumedInFinalSegment = 0f
        animationState = animationState.copy(value = 0f)
        animationState.animateTo(
            targetValue = remainingOffset.toFloat(),
            sequentialAnimation = animationState.velocity != 0f,
        ) {
            val requested = value - consumedInFinalSegment
            if (requested > 0f) {
                val consumed = scrollBy(requested)
                consumedInFinalSegment += consumed
                if (consumed < requested - ScrollConsumptionTolerancePx) {
                    cancelAnimation()
                }
            }
        }
    }
}

/**
 * Non-suspending variant of [scrollToBottom]: records the scroll request
 * synchronously so it is applied during the next layout pass. Prefer this in
 * `LaunchedEffect`s driven by recomposition (e.g. entering a session): a
 * suspending scroll launched from such an effect may never resume before the
 * composition goes idle, leaving the list stuck at the top.
 *
 * Requests stay bounded. When the tail is already measured, the exact
 * remaining distance is requested from the current anchor. Otherwise the item
 * lands with its top edge aligned and the tail-layout watcher applies the
 * measured remainder once layout catches up. An unbounded `Int.MAX_VALUE`
 * offset is avoided on purpose: Android's lazy-list implementation can
 * overflow or stop one item early with it while the target is still
 * off-screen, which surfaces as a visibly staged landing.
 */
internal fun LazyListState.requestScrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return
    if (compensateVisibleTailToBottom()) return
    requestScrollToItem(lastIndex)
}

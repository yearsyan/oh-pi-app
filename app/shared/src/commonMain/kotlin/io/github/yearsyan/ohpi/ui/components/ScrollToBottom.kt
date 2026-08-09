package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.lazy.LazyListState

/**
 * Scrolls so the very end of the list content (including bottom content padding)
 * becomes visible.
 *
 * Plain `scrollToItem(index)` aligns the item's top edge with the viewport top.
 * When the last item is taller than the viewport — a long assistant run is one
 * grouped item and can easily be — that stops at the item's top and leaves its
 * tail below the fold. The second, measured offset reaches the actual tail
 * without using an overflow-prone unbounded offset.
 */
internal suspend fun LazyListState.scrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return

    // First make the last item measurable without relying on an unbounded
    // offset. Android's lazy-list implementation can overflow or stop one item
    // early when Int.MAX_VALUE is used while the target is still off-screen.
    scrollToItem(lastIndex)
    val info = layoutInfo
    val lastItem = info.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val contentEnd = info.viewportEndOffset - info.afterContentPadding
    val remainingOffset = lastItem.offset + lastItem.size - contentEnd
    if (remainingOffset > 0) {
        scrollToItem(lastIndex, scrollOffset = remainingOffset)
    }
}

/**
 * Animated variant of [scrollToBottom]. Both animation steps use bounded
 * offsets: first bring the tail item into layout, then animate through the
 * measured remainder when that grouped item is taller than the viewport.
 */
internal suspend fun LazyListState.animateScrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return

    animateScrollToItem(lastIndex)
    val info = layoutInfo
    val lastItem = info.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val contentEnd = info.viewportEndOffset - info.afterContentPadding
    val remainingOffset = lastItem.offset + lastItem.size - contentEnd
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

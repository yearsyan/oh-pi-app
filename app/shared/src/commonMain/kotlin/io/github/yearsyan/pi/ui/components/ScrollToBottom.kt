package io.github.yearsyan.pi.ui.components

import androidx.compose.foundation.lazy.LazyListState

/**
 * Scrolls so the very end of the list content (including bottom content padding)
 * becomes visible.
 *
 * Plain `scrollToItem(index)` aligns the item's top edge with the viewport top.
 * When the last item is taller than the viewport — a long assistant run is one
 * grouped item and can easily be — that stops at the item's top and leaves its
 * tail below the fold. Requesting the max scroll offset clamps to the true
 * bottom in both cases.
 */
internal suspend fun LazyListState.scrollToBottom(lastIndex: Int) {
    if (lastIndex >= 0) scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
}

/** Animated variant of [scrollToBottom]. */
internal suspend fun LazyListState.animateScrollToBottom(lastIndex: Int) {
    if (lastIndex >= 0) animateScrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
}

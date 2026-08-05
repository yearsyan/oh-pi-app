package io.github.yearsyan.ohpi.ui.components

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

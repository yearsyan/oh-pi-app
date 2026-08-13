package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    if (scrollState.maxValue <= 0) return
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
    )
}

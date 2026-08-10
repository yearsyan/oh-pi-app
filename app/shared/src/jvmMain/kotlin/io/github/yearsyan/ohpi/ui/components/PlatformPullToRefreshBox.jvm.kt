package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) { content() }
}

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Visible desktop scrollbar for a shared [ScrollState]; mobile targets render nothing. */
@Composable
internal expect fun PlatformVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

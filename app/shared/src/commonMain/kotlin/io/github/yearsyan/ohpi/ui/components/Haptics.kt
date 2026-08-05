package io.github.yearsyan.ohpi.ui.components

/**
 * Triggers a medium-strength haptic tick for a long-press.
 *
 * On Android the system already emits the LongPress haptic for
 * [androidx.compose.foundation.combinedClickable], so the actual is a no-op.
 */
internal expect fun longPressHaptic()

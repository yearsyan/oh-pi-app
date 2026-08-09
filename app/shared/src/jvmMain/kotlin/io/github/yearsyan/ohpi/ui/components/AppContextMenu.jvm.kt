package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

@Composable
internal actual fun AppContextMenu(
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalContextMenuRepresentation provides RoundedContextMenuRepresentation,
    ) {
        ContextMenuArea(
            items = {
                items.map { item ->
                    ContextMenuItem(
                        label = item.title,
                        enabled = item.enabled,
                        onClick = { onItemClick(item.id) },
                    )
                }
            },
            enabled = enabled && items.isNotEmpty(),
            content = content,
        )
    }
}

private object RoundedContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        val open = state.status as? ContextMenuState.Status.Open ?: return
        val clickOffset =
            IntOffset(open.rect.left.roundToInt(), open.rect.top.roundToInt())
        val positionProvider = remember(clickOffset) { CursorPopupPositionProvider(clickOffset) }

        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 140.dp, max = 280.dp)
                        .padding(vertical = 4.dp),
                ) {
                    items().forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                state.status = ContextMenuState.Status.Closed
                                item.onClick()
                            },
                            enabled = item.enabled,
                        )
                    }
                }
            }
        }
    }
}

private class CursorPopupPositionProvider(
    private val clickOffset: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(
            x = (anchorBounds.left + clickOffset.x).coerceIn(0, maxX),
            y = (anchorBounds.top + clickOffset.y).coerceIn(0, maxY),
        )
    }
}

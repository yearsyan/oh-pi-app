package io.github.yearsyan.ohpi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope

private enum class WindowControlIcon {
    Minimize,
    Maximize,
    Restore,
    Close,
}

@Composable
fun WindowScope.DesktopTitleBar(
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(39.dp)
                .background(colors.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowDraggableArea(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(22.dp)
                            .background(colors.primary, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "π",
                        color = colors.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "Pi",
                    color = colors.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        WindowControlButton(
            icon = WindowControlIcon.Minimize,
            label = "Minimize",
            onClick = onMinimize,
        )
        WindowControlButton(
            icon = if (isMaximized) WindowControlIcon.Restore else WindowControlIcon.Maximize,
            label = if (isMaximized) "Restore" else "Maximize",
            onClick = onToggleMaximize,
        )
        WindowControlButton(
            icon = WindowControlIcon.Close,
            label = "Close",
            destructive = true,
            onClick = onClose,
        )
    }
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun WindowControlButton(
    icon: WindowControlIcon,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val background =
        when {
            destructive && hovered -> Color(0xFFE81123)
            hovered -> colors.onSurface.copy(alpha = 0.09f)
            else -> Color.Transparent
        }
    val foreground =
        if (destructive && hovered) Color.White else colors.onSurfaceVariant

    Box(
        modifier =
            Modifier
                .width(46.dp)
                .fillMaxHeight()
                .background(background)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        WindowControlGlyph(
            icon = icon,
            color = foreground,
        )
    }
}

@Composable
private fun WindowControlGlyph(
    icon: WindowControlIcon,
    color: Color,
) {
    Canvas(Modifier.size(12.dp)) {
        val strokeWidth = 1.2.dp.toPx()
        when (icon) {
            WindowControlIcon.Minimize ->
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.18f, size.height * 0.63f),
                    end = Offset(size.width * 0.82f, size.height * 0.63f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Square,
                )

            WindowControlIcon.Maximize ->
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.19f, size.height * 0.19f),
                    size = Size(size.width * 0.62f, size.height * 0.62f),
                    style = Stroke(width = strokeWidth),
                )

            WindowControlIcon.Restore -> {
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.29f, size.height * 0.17f),
                    size = Size(size.width * 0.57f, size.height * 0.57f),
                    style = Stroke(width = strokeWidth),
                )
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.14f, size.height * 0.32f),
                    size = Size(size.width * 0.57f, size.height * 0.57f),
                    style = Stroke(width = strokeWidth),
                )
            }

            WindowControlIcon.Close -> {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.2f, size.height * 0.2f),
                    end = Offset(size.width * 0.8f, size.height * 0.8f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.8f, size.height * 0.2f),
                    end = Offset(size.width * 0.2f, size.height * 0.8f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Square,
                )
            }
        }
    }
}

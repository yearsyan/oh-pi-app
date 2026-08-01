package io.github.yearsyan.pi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.pi.chat.AssistantBlock
import io.github.yearsyan.pi.chat.BlockKind
import io.github.yearsyan.pi.chat.TimelineItem
import io.github.yearsyan.pi.chat.ToolCallView
import io.github.yearsyan.pi.chat.ToolState
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.markdown.MarkdownView
import io.github.yearsyan.pi.theme.piExtras
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info

@Composable
fun UserMessageRow(item: TimelineItem.UserItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = piExtras.userBubble,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Text(
                item.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                color = piExtras.onUserBubble,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            )
        }
    }
}

@Composable
fun ThinkingCard(block: AssistantBlock) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(active = false, color = MaterialTheme.colorScheme.tertiary, size = 6)
            Spacer(Modifier.width(8.dp))
            Text(
                S.thinking,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).alpha(0.7f),
            )
        }
        AnimatedVisibility(open) {
            Text(
                block.text,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ToolCallCard(tool: ToolCallView) {
    var open by remember { mutableStateOf(false) }
    val extras = piExtras
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Build,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tool.name.ifBlank { "tool" },
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ToolStateBadge(tool)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).alpha(0.7f),
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                if (tool.args.isNotBlank()) {
                    ToolSectionLabel(S.toolInput)
                    ToolCodeBlock(tool.args)
                }
                if (tool.output.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    ToolSectionLabel(S.toolOutput)
                    ToolCodeBlock(tool.output)
                }
            }
        }
    }
}

@Composable
private fun ToolStateBadge(tool: ToolCallView) {
    val extras = piExtras
    when (tool.state) {
        ToolState.Running, ToolState.Streaming -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                S.toolRunning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        ToolState.Pending -> Text(
            "…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToolState.Done -> Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = extras.success,
            modifier = Modifier.size(14.dp),
        )
        ToolState.Error -> Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 3.dp),
    )
}

@Composable
private fun ToolCodeBlock(text: String) {
    Surface(
        color = piExtras.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            ),
            color = piExtras.onCode,
            maxLines = 40,
        )
    }
}

@Composable
fun AssistantMessageRow(item: TimelineItem.AssistantItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item.blocks.forEach { block ->
                when (block.kind) {
                    BlockKind.Thinking -> if (block.text.isNotBlank()) ThinkingCard(block)
                    BlockKind.Text -> if (block.text.isNotBlank()) MarkdownView(block.text)
                    BlockKind.ToolCall -> block.tool?.let { ToolCallCard(it) }
                }
            }
            if (item.streaming) StreamingCaret()
            item.stopReason?.let { reason ->
                val label = when (reason) {
                    "stop", "" -> ""
                    "length" -> S.stopLength
                    "toolUse" -> S.stopToolUse
                    "error" -> S.stopError
                    "aborted" -> S.stopAborted
                    else -> reason
                }
                if (label.isNotEmpty()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!item.model.isNullOrBlank() && !item.streaming) {
                Text(
                    item.model!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
fun StreamingCaret() {
    PulsingDot(active = true, color = MaterialTheme.colorScheme.primary, size = 8)
}

@Composable
fun PulsingDot(active: Boolean, color: androidx.compose.ui.graphics.Color, size: Int) {
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseAlpha",
        ).value
    } else 0.6f
    Box(
        Modifier
            .padding(top = 4.dp)
            .size(size.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun StatusLine(item: TimelineItem.StatusItem) {
    val color = when (item.tone) {
        TimelineItem.StatusItem.Tone.Info -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        TimelineItem.StatusItem.Tone.Warn -> piExtras.warning
        TimelineItem.StatusItem.Tone.Error -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 3,
        )
    }
}

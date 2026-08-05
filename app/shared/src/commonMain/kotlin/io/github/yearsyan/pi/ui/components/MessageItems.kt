package io.github.yearsyan.pi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.pi.chat.AssistantProcessDetail
import io.github.yearsyan.pi.chat.AssistantRenderChunk
import io.github.yearsyan.pi.chat.AssistantBlock
import io.github.yearsyan.pi.chat.ProcessSummary
import io.github.yearsyan.pi.chat.TimelineItem
import io.github.yearsyan.pi.chat.ToolAction
import io.github.yearsyan.pi.chat.ToolCallView
import io.github.yearsyan.pi.chat.ToolActionKind
import io.github.yearsyan.pi.chat.ToolInputView
import io.github.yearsyan.pi.chat.ToolState
import io.github.yearsyan.pi.chat.chunkAssistantRun
import io.github.yearsyan.pi.chat.summarizeProcessDetails
import io.github.yearsyan.pi.chat.toolAction
import io.github.yearsyan.pi.chat.toolInputView
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.i18n.Strings
import io.github.yearsyan.pi.markdown.MarkdownView
import io.github.yearsyan.pi.theme.piExtras
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal

@Composable
fun UserMessageRow(item: TimelineItem.UserItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UserImageGallery(item.images)
            if (item.text.isNotBlank()) {
                Surface(
                    color = piExtras.userBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
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
    }
}

@Composable
private fun AgentProcessBlock(
    details: List<AssistantProcessDetail>,
    isStreaming: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val showDetails = expanded
    val strings = S
    // streaming shows the live latest activity; a completed block collapses
    // into an aggregated summary (思考 2 次，写入 1 个文件，…)
    val summary =
        if (isStreaming) {
            when (val latest = details.lastOrNull()) {
                is AssistantProcessDetail.Tool -> friendlyToolAction(strings, latest.tool)
                else -> strings.thinkingInProgress
            }
        } else {
            completedProcessSummary(strings, summarizeProcessDetails(details))
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SweepingText(
                text = summary,
                sweeping = isStreaming,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                highlightColor = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(16.dp)
                        .alpha(0.7f)
                        .graphicsLayer { rotationZ = if (showDetails) 180f else 0f },
            )
        }
        AnimatedVisibility(showDetails) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                details.forEachIndexed { index, detail ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    when (detail) {
                        is AssistantProcessDetail.Thinking -> ThinkingDetail(detail.block)
                        is AssistantProcessDetail.Tool -> ToolDetail(detail.tool)
                    }
                }
            }
        }
    }
}

/** One-line label with a light band sweeping across the text while [sweeping] is true. */
@Composable
private fun SweepingText(
    text: String,
    sweeping: Boolean,
    color: Color,
    highlightColor: Color,
    style: TextStyle,
    fontStyle: FontStyle? = null,
    modifier: Modifier = Modifier,
) {
    var laidOutWidth by remember { mutableIntStateOf(0) }
    val baseStyle = style.copy(color = color, fontStyle = fontStyle)
    if (!sweeping) {
        Text(
            text,
            style = baseStyle,
            modifier = modifier,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val progress by
        rememberInfiniteTransition(label = "sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
            label = "sweepProgress",
        )
    val width = laidOutWidth
    val brush =
        if (width > 0) {
            val band = width * 0.45f + 1f
            val center = -band + progress * (width + 2 * band)
            Brush.linearGradient(
                colorStops =
                    arrayOf(
                        0.0f to color,
                        0.5f to highlightColor,
                        1.0f to color,
                    ),
                start = Offset(center - band, 0f),
                end = Offset(center + band, 0f),
            )
        } else null
    Text(
        text,
        style = if (brush != null) baseStyle.copy(brush = brush) else baseStyle,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { laidOutWidth = it.size.width },
    )
}

@Composable
private fun ThinkingDetail(block: AssistantBlock) {
    Text(
        S.thinking,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (block.text.isNotBlank()) {
        Text(
            block.text,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolDetail(tool: ToolCallView) {
    val strings = S
    val action = toolAction(tool.name, tool.args)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            toolActionIcon(action.kind),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            friendlyToolAction(strings, action),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ToolStateBadge(tool)
    }
    when (val input = toolInputView(tool.name, tool.args)) {
        is ToolInputView.Command -> {
            Spacer(Modifier.height(8.dp))
            BashCommandBlock(input.command)
        }
        is ToolInputView.FileContent -> {
            Spacer(Modifier.height(8.dp))
            ToolCodeBlock(input.content)
        }
        is ToolInputView.FileEdit -> {
            Spacer(Modifier.height(8.dp))
            EditDiffBlock(input.hunks)
        }
        is ToolInputView.Raw -> {
            Spacer(Modifier.height(8.dp))
            ToolSectionLabel(S.toolInput)
            ToolCodeBlock(input.args)
        }
        ToolInputView.Hidden -> Unit
    }
    if (tool.output.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        ToolSectionLabel(S.toolOutput)
        ToolCodeBlock(tool.output)
    }
}

private fun friendlyToolAction(strings: Strings, tool: ToolCallView): String =
    friendlyToolAction(strings, toolAction(tool.name, tool.args))

private fun friendlyToolAction(strings: Strings, action: ToolAction): String =
    when (action.kind) {
        ToolActionKind.Execute -> strings.toolExecuted(action.target)
        ToolActionKind.Read -> strings.toolRead(action.target)
        ToolActionKind.Write -> strings.toolWrote(action.target)
        ToolActionKind.Edit -> strings.toolEdited(action.target)
        ToolActionKind.Search -> strings.toolSearched(action.target)
        ToolActionKind.List -> strings.toolListed(action.target)
        ToolActionKind.Call -> strings.toolCalled(action.target)
    }

private fun toolActionIcon(kind: ToolActionKind): ImageVector =
    when (kind) {
        ToolActionKind.Execute -> Icons.Outlined.Terminal
        ToolActionKind.Read -> Icons.Outlined.Description
        ToolActionKind.Write -> Icons.Outlined.Create
        ToolActionKind.Edit -> Icons.Outlined.Edit
        ToolActionKind.Search -> Icons.Outlined.Search
        ToolActionKind.List -> Icons.Outlined.Folder
        ToolActionKind.Call -> Icons.Outlined.Build
    }

/** Aggregated one-line summary for a completed process block; thinking comes last. */
internal fun completedProcessSummary(strings: Strings, summary: ProcessSummary): String {
    val parts = mutableListOf<String>()
    if (summary.filesWritten > 0) parts += strings.processWroteFiles(summary.filesWritten)
    if (summary.filesRead > 0) parts += strings.processReadFiles(summary.filesRead)
    if (summary.commandsRun > 0) parts += strings.processRanCommands(summary.commandsRun)
    if (summary.searches > 0) parts += strings.processSearchedTimes(summary.searches)
    if (summary.directoryListings > 0) parts += strings.processListedDirectories(summary.directoryListings)
    if (summary.otherToolCalls > 0) parts += strings.processCalledTools(summary.otherToolCalls)
    // the thinking part is always described last: 进行了思考 / 思考 n 次
    if (summary.thinkingCount == 1) {
        parts += strings.processThoughtOnce
    } else if (summary.thinkingCount > 1) {
        parts += strings.processThoughtTimes(summary.thinkingCount)
    }
    if (parts.isEmpty()) return strings.thinking
    return parts.joinToString(strings.processSummarySeparator)
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

private const val MAX_DIFF_LINES = 18
private const val MAX_DIFF_HUNKS = 6

/** Removed/added line pairs for edit tool payloads, styled like a unified diff. */
@Composable
private fun EditDiffBlock(hunks: List<ToolInputView.EditHunk>) {
    Surface(
        color = piExtras.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hunks.take(MAX_DIFF_HUNKS).forEachIndexed { index, hunk ->
                if (index > 0) {
                    HorizontalDivider(color = piExtras.onCode.copy(alpha = 0.12f))
                }
                DiffLines(hunk.oldText, prefix = "-", color = MaterialTheme.colorScheme.error)
                DiffLines(hunk.newText, prefix = "+", color = piExtras.success)
            }
            if (hunks.size > MAX_DIFF_HUNKS) {
                Text(
                    "\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = piExtras.onCode,
                )
            }
        }
    }
}

@Composable
private fun DiffLines(
    text: String,
    prefix: String,
    color: Color,
) {
    if (text.isEmpty()) return
    val lines = text.lines()
    val shown = lines.take(MAX_DIFF_LINES)
    Text(
        shown.joinToString("\n") { "$prefix $it" } + if (lines.size > shown.size) "\n…" else "",
        style =
            MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            ),
        color = color,
    )
}

/** Terminal-style block for shell commands: green "$" prompt plus the command text. */
@Composable
private fun BashCommandBlock(command: String) {
    Surface(
        color = piExtras.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "$",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
                color = piExtras.success,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                command,
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
}

@Composable
fun AssistantRunRow(
    items: List<TimelineItem>,
    isStreaming: Boolean,
) {
    val chunks = chunkAssistantRun(items)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chunks.forEachIndexed { index, chunk ->
                when (chunk) {
                    is AssistantRenderChunk.Process ->
                        AgentProcessBlock(
                            details = chunk.details,
                            isStreaming = isStreaming && index == chunks.lastIndex,
                        )
                    is AssistantRenderChunk.Text -> MarkdownView(chunk.block.text)
                }
            }
            val lastChunkIsText = chunks.lastOrNull() is AssistantRenderChunk.Text
            if (isStreaming && (chunks.isEmpty() || lastChunkIsText)) {
                StreamingCaret()
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
            overflow = TextOverflow.Ellipsis,
        )
    }
}

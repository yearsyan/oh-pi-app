package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.ohpi.PlatformTarget
import io.github.yearsyan.ohpi.chat.AssistantProcessDetail
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.chat.AssistantRenderChunk
import io.github.yearsyan.ohpi.chat.AssistantBlock
import io.github.yearsyan.ohpi.chat.ProcessSummary
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.ToolAction
import io.github.yearsyan.ohpi.chat.ToolCallView
import io.github.yearsyan.ohpi.chat.ToolActionKind
import io.github.yearsyan.ohpi.chat.ToolInputView
import io.github.yearsyan.ohpi.chat.ToolState
import io.github.yearsyan.ohpi.chat.chunkAssistantRun
import io.github.yearsyan.ohpi.chat.fileNameOf
import io.github.yearsyan.ohpi.chat.summarizeProcessDetails
import io.github.yearsyan.ohpi.chat.toolAction
import io.github.yearsyan.ohpi.chat.toolInputView
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.markdown.MarkdownView
import io.github.yearsyan.ohpi.syntax.MAX_HIGHLIGHT_LENGTH
import io.github.yearsyan.ohpi.syntax.Syntax
import io.github.yearsyan.ohpi.theme.piExtras
import io.github.yearsyan.ohpi.theme.rememberCodeFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal

internal const val AGENT_PROCESS_BLOCK_TEST_TAG = "agent-process-block"

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
                // Long-press starts text selection, mirroring the assistant side.
                SelectionContainer {
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
}

@Composable
private fun AgentProcessBlock(
    details: List<AssistantProcessDetail>,
    isStreaming: Boolean,
    onDetailsToggled: (expanding: Boolean) -> Unit,
    onDetailsExpanded: () -> Unit,
    onLoadToolImage: (suspend (String) -> ByteArray)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val detailsVisibility = remember { MutableTransitionState(false) }
    detailsVisibility.targetState = expanded
    val latestOnDetailsExpanded by rememberUpdatedState(onDetailsExpanded)
    var sheetContent by remember { mutableStateOf<ProcessSheetContent?>(null) }
    val showDetails = expanded
    val strings = S

    // Notify the list once the expansion animation has reached its final
    // height so a bottom-anchored expansion can apply its final correction.
    LaunchedEffect(detailsVisibility.isIdle, detailsVisibility.currentState) {
        if (detailsVisibility.isIdle && detailsVisibility.currentState) {
            latestOnDetailsExpanded()
        }
    }
    // A block with a single detail (one thinking or one tool call) skips the
    // inline list entirely: tapping the header opens the detail sheet directly.
    // A blank thinking (e.g. encrypted reasoning from GPT models) has nothing
    // to show, so neither the header nor the row is interactive.
    val singleDetail = details.singleOrNull()
    val singleSheetDetail =
        when (singleDetail) {
            is AssistantProcessDetail.Thinking ->
                singleDetail.takeIf { it.block.text.isNotBlank() }
            else -> singleDetail
        }
    val headerInteractive = singleDetail == null || singleSheetDetail != null
    val isDesktop = remember { getPlatform().target == PlatformTarget.Desktop }
    var headerHovered by remember { mutableStateOf(false) }
    // streaming shows the live latest activity; a completed block collapses
    // into an aggregated summary (思考 2 次，写入 1 个文件，…)
    val summary =
        if (isStreaming) {
            when (val latest = details.lastOrNull()) {
                is AssistantProcessDetail.Tool -> friendlyToolHeader(strings, latest.tool)
                else -> strings.thinkingInProgress
            }
        } else {
            completedProcessSummary(strings, summarizeProcessDetails(details))
        }
    // Plain, card-less layout: the block sits directly on the page background,
    // with no rounded grey container around the header or the detail rows.
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AGENT_PROCESS_BLOCK_TEST_TAG),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .hoverFadeClickable(
                        enabled = headerInteractive,
                        onHoverChange = { headerHovered = it },
                    ) {
                        when {
                            singleSheetDetail is AssistantProcessDetail.Thinking ->
                                sheetContent =
                                    ProcessSheetContent.Thinking(singleSheetDetail.block)
                            singleSheetDetail is AssistantProcessDetail.Tool ->
                                sheetContent =
                                    ProcessSheetContent.Tool(singleSheetDetail.tool)
                            singleDetail == null -> {
                                val expanding = !expanded
                                expanded = expanding
                                onDetailsToggled(expanding)
                            }
                        }
                    }
                    .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SweepingText(
                text = summary,
                sweeping = isStreaming,
                // Deeper base text plus a fully-opaque highlight band makes the
                // sweep read clearly (previously the band blended into a light
                // grey base).
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                highlightColor = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontStyle = FontStyle.Italic,
                // fill=false: the text only takes the width it needs so the
                // expand/collapse arrow sits right at the end of the text
                // instead of at the far right edge of the row.
                modifier = Modifier.weight(1f, fill = false),
            )
            // Desktop hides the collapsed arrow until the header is hovered;
            // touch platforms always show it.
            val arrowVisible = !isDesktop || showDetails || headerHovered
            if (headerInteractive && arrowVisible) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    // Sheet headers navigate (right); inline-expanding headers
                    // rotate the same arrow to point down while expanded.
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .size(16.dp)
                            .alpha(0.7f)
                            .graphicsLayer {
                                rotationZ =
                                    if (singleSheetDetail == null && showDetails) 90f else 0f
                            },
                )
            }
        }
        AnimatedVisibility(visibleState = detailsVisibility) {
            Column(Modifier.padding(bottom = 4.dp)) {
                details.forEach { detail ->
                    when (detail) {
                        is AssistantProcessDetail.Thinking ->
                            ThinkingRow(
                                detail.block,
                                onClick = {
                                    sheetContent =
                                        ProcessSheetContent.Thinking(detail.block)
                                },
                            )
                        is AssistantProcessDetail.Tool ->
                            ToolRow(
                                detail.tool,
                                onClick = {
                                    sheetContent = ProcessSheetContent.Tool(detail.tool)
                                },
                            )
                    }
                }
            }
        }
    }
    sheetContent?.let { content ->
        ProcessDetailSheet(
            content = content,
            cacheMarkdown = !isStreaming,
            onDismiss = { sheetContent = null },
            onLoadToolImage = onLoadToolImage,
        )
    }
}

/** Payload of the process-detail bottom sheet: full thinking text or a full tool call. */
private sealed interface ProcessSheetContent {
    data class Thinking(val block: AssistantBlock) : ProcessSheetContent

    data class Tool(val tool: ToolCallView) : ProcessSheetContent
}

/** Bottom sheet showing the full thinking content or the full tool call detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessDetailSheet(
    content: ProcessSheetContent,
    cacheMarkdown: Boolean,
    onDismiss: () -> Unit,
    onLoadToolImage: (suspend (String) -> ByteArray)? = null,
) {
    // Cap the sheet content at 80% of the window height; taller content scrolls
    // inside. The constraint goes on the content, not on ModalBottomSheet itself:
    // constraining the sheet modifier breaks its bottom anchoring.
    // (LocalConfiguration is unavailable in commonMain, so derive it from the window.)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = windowHeight * 0.8f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            when (content) {
                is ProcessSheetContent.Thinking -> ThinkingDetail(content.block, cacheMarkdown)
                is ProcessSheetContent.Tool -> ToolDetail(content.tool, onLoadToolImage)
            }
        }
    }
}

/**
 * Click handling without any ripple or background highlight: the only hover
 * feedback is the row content fading. No padding is added, so the text stays
 * flush with the surrounding body text.
 */
@Composable
private fun Modifier.hoverFadeClickable(
    enabled: Boolean,
    onHoverChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    if (!enabled) return this
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    if (onHoverChange != null) {
        LaunchedEffect(hovered) { onHoverChange(hovered) }
    }
    return hoverable(interactionSource)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        .alpha(if (hovered) 0.5f else 1f)
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
            // Plateau gradient: the highlight holds at full strength across the
            // middle of the band instead of peaking at a single point, so the
            // sweep looks like a solid dark band moving through the text.
            Brush.linearGradient(
                colorStops =
                    arrayOf(
                        0.0f to color,
                        0.35f to highlightColor,
                        0.65f to highlightColor,
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

/** Compact expanded-row for a thinking detail: brain icon, label, one preview line; tap opens the sheet. */
@Composable
private fun ThinkingRow(
    block: AssistantBlock,
    onClick: () -> Unit,
) {
    val preview = block.text.trim().replace(Regex("\\s+"), " ")
    // Blank thinking (e.g. encrypted reasoning) has no detail to show in a sheet.
    val hasContent = block.text.isNotBlank()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .hoverFadeClickable(enabled = hasContent, onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            S.thinking,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            preview,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact expanded-row for a tool call: one header line only; tap opens the sheet. */
@Composable
private fun ToolRow(
    tool: ToolCallView,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .hoverFadeClickable(enabled = true, onClick = onClick)
                .padding(vertical = 6.dp),
    ) {
        ToolHeader(tool)
    }
}

/** Full thinking content shown in the bottom sheet; thinking is markdown, like visible text. */
@Composable
private fun ThinkingDetail(block: AssistantBlock, cacheMarkdown: Boolean) {
    Text(
        S.thinking,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (block.text.isNotBlank()) {
        MarkdownView(
            block.text,
            modifier = Modifier.padding(top = 4.dp),
            cacheable = cacheMarkdown,
        )
    }
}

/** Full tool call detail (header + input + output) shown in the bottom sheet. */
@Composable
private fun ToolDetail(
    tool: ToolCallView,
    onLoadToolImage: (suspend (String) -> ByteArray)? = null,
) {
    val action = toolAction(tool.name, tool.args)
    // File tools: the header drops the inline target and the full path goes into
    // a wrapping block — long paths don't fit on the single ellipsized header line.
    val isFileTool =
        action.kind == ToolActionKind.Read ||
            action.kind == ToolActionKind.Write ||
            action.kind == ToolActionKind.Edit
    val imageTarget = readImageTarget(tool, action)
    Column(Modifier.fillMaxWidth()) {
        ToolHeader(tool, showTarget = !isFileTool)
        if (isFileTool) {
            Spacer(Modifier.height(8.dp))
            ToolCodeBlock(action.target)
        }
        if (imageTarget != null && onLoadToolImage != null) {
            Spacer(Modifier.height(8.dp))
            ToolImageEntry(imageTarget, onLoadToolImage)
        }
        ToolInputSection(tool)
        if (tool.output.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            ToolSectionLabel(S.toolOutput)
            ToolCodeBlock(tool.output)
        }
    }
}

/** Image extensions decodable by the fullscreen viewer (gif/ico render as still frames). */
private val ReadImageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "ico")

/**
 * Target path of a read tool call that read an image, or null. The path wins
 * when it carries a known image extension; otherwise the read tool's own
 * output header ("Read image file [image/…]") is the signal — pi emits it
 * even for extension-less paths.
 */
private fun readImageTarget(tool: ToolCallView, action: ToolAction): String? {
    if (action.kind != ToolActionKind.Read) return null
    val extension = action.target.substringAfterLast('.', "").lowercase()
    if (extension in ReadImageExtensions) return action.target
    return action.target.takeIf { tool.output.startsWith("Read image file [image/") }
}

/** Clickable row in a read-image detail sheet opening the fullscreen viewer. */
@Composable
private fun ToolImageEntry(
    path: String,
    onLoadImage: suspend (String) -> ByteArray,
) {
    var previewOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = { previewOpen = true },
        color = piExtras.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = piExtras.onCode,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                S.toolViewImage,
                style = MaterialTheme.typography.bodySmall,
                color = piExtras.onCode,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp).alpha(0.7f),
                tint = piExtras.onCode,
            )
        }
    }
    if (previewOpen) {
        ToolReadImageDialog(
            path = path,
            onLoadImage = onLoadImage,
            onDismiss = { previewOpen = false },
        )
    }
}

/**
 * Fullscreen viewer for a gateway-side image read by the read tool. The raw
 * bytes come from the gateway download endpoint (the text read endpoint
 * rejects binaries) and decode off the main thread.
 */
@Composable
private fun ToolReadImageDialog(
    path: String,
    onLoadImage: suspend (String) -> ByteArray,
    onDismiss: () -> Unit,
) {
    val state by produceState<ImagePreviewState>(ImagePreviewState.Loading, path) {
        value =
            runCatching {
                val bytes = onLoadImage(path)
                ImagePreviewState.Ready(
                    withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() },
                    bytes,
                )
            }.getOrElse { ImagePreviewState.Failed(it.message) }
    }
    ImagePreviewDialog(
        state = state,
        onDismiss = onDismiss,
        title = fileNameOf(path),
        subtitle = path,
    )
}

@Composable
private fun ToolHeader(
    tool: ToolCallView,
    showTarget: Boolean = true,
) {
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
            friendlyToolVerb(strings, action),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (showTarget && action.target.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            // Same light italic style as the thinking preview, so all detail
            // text in the expanded process list reads uniformly.
            Text(
                action.target,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        ToolStateBadge(tool)
    }
}

@Composable
private fun ToolInputSection(tool: ToolCallView) {
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
}

private fun friendlyToolAction(strings: Strings, tool: ToolCallView): String =
    friendlyToolAction(strings, toolAction(tool.name, tool.args))

/** Verb-only label for the detail row; the target is styled separately. */
private fun friendlyToolVerb(strings: Strings, action: ToolAction): String =
    when (action.kind) {
        ToolActionKind.Execute -> strings.toolVerbExecuted
        ToolActionKind.Read -> strings.toolVerbRead
        ToolActionKind.Write -> strings.toolVerbWrote
        ToolActionKind.Edit -> strings.toolVerbEdited
        ToolActionKind.Search -> strings.toolVerbSearched
        ToolActionKind.List -> strings.toolVerbListed
        ToolActionKind.Call -> strings.toolVerbCalled
    }

/** Full path in the expanded detail: "Edited /path/to/MessageItems.kt". */
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

/** Basename only in the collapsed block header: "Edited MessageItems.kt". */
private fun friendlyToolHeader(strings: Strings, tool: ToolCallView): String =
    friendlyToolHeader(strings, toolAction(tool.name, tool.args))

private fun friendlyToolHeader(strings: Strings, action: ToolAction): String =
    when (action.kind) {
        ToolActionKind.Read -> strings.toolRead(fileNameOf(action.target))
        ToolActionKind.Write -> strings.toolWrote(fileNameOf(action.target))
        ToolActionKind.Edit -> strings.toolEdited(fileNameOf(action.target))
        else -> friendlyToolAction(strings, action)
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
    if (summary.filesWritten > 0) {
        parts +=
            if (summary.writtenFiles.size == 1) {
                strings.processWroteFile(fileNameOf(summary.writtenFiles.first()))
            } else {
                strings.processWroteFiles(summary.filesWritten)
            }
    }
    if (summary.filesRead > 0) {
        parts +=
            if (summary.readFiles.size == 1) {
                strings.processReadFile(fileNameOf(summary.readFiles.first()))
            } else {
                strings.processReadFiles(summary.filesRead)
            }
    }
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
        // Selectable so tool output (and other payloads) can be copied from the
        // detail sheet.
        SelectionContainer {
            Text(
                text,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = rememberCodeFontFamily(),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
                color = piExtras.onCode,
                maxLines = 40,
            )
        }
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
                fontFamily = rememberCodeFontFamily(),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            ),
        color = color,
    )
}

/** Terminal-style block for shell commands: green "$" prompt plus the command text. */
@Composable
private fun BashCommandBlock(command: String) {
    val extras = piExtras
    val shellSpec = remember { requireNotNull(Syntax.specForLangName("bash")) }
    val highlighted by produceState(
        initialValue = AnnotatedString(command),
        key1 = command,
        key2 = shellSpec,
        key3 = extras.syntax,
    ) {
        if (command.length <= MAX_HIGHLIGHT_LENGTH) {
            value = withContext(Dispatchers.Default) {
                Syntax.highlight(command, shellSpec, extras.syntax)
            }
        }
    }
    Surface(
        color = extras.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        // Trailing end padding keeps the command tail from
                        // sliding under the floating copy button.
                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 36.dp),
            ) {
                val codeFont = rememberCodeFontFamily()
                Text(
                    "$",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = codeFont,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    ),
                    color = piExtras.success,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    highlighted,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = codeFont,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    ),
                    color = extras.syntax.plain,
                    maxLines = 40,
                    softWrap = false,
                )
            }
            // The highlighted command is a non-wrapping AnnotatedString in a
            // horizontal scroll row, so selection is impractical — copy the
            // whole command via this button instead.
            CopyCommandButton(
                command = command,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** Small floating button copying the whole shell command; flips to a check briefly. */
@Composable
private fun CopyCommandButton(
    command: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(command))
            copied = true
        },
        modifier = modifier
            .padding(2.dp)
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(piExtras.codeBackground),
    ) {
        Icon(
            if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            contentDescription = S.copy,
            modifier = Modifier.size(14.dp),
            tint = if (copied) piExtras.success else piExtras.onCode.copy(alpha = 0.7f),
        )
    }
}

@Composable
fun AssistantRunRow(
    items: List<TimelineItem>,
    isStreaming: Boolean,
    isTailRun: Boolean = false,
    onProcessDetailsToggled: (expanding: Boolean, isTailProcess: Boolean) -> Unit = { _, _ -> },
    onProcessDetailsExpanded: (isTailProcess: Boolean) -> Unit = {},
    onLoadToolImage: (suspend (String) -> ByteArray)? = null,
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
                    is AssistantRenderChunk.Process -> {
                        val isTailProcess = isTailRun && index == chunks.lastIndex
                        AgentProcessBlock(
                            details = chunk.details,
                            isStreaming = isStreaming && index == chunks.lastIndex,
                            onDetailsToggled = { expanding ->
                                onProcessDetailsToggled(expanding, isTailProcess)
                            },
                            onDetailsExpanded = {
                                onProcessDetailsExpanded(isTailProcess)
                            },
                            onLoadToolImage = onLoadToolImage,
                        )
                    }
                    is AssistantRenderChunk.Text ->
                        MarkdownView(
                            markdown = chunk.block.text,
                            cacheable = !isStreaming,
                        )
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

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.github.yearsyan.ohpi.chat.BlockKind
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.TimelineRenderGroup
import io.github.yearsyan.ohpi.i18n.S
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Width at which the transcript has enough spare room for side navigation. */
internal val WideConversationBreakpoint = 900.dp

/** Keeps long desktop/tablet lines readable while compact layouts continue to use all available width. */
internal val ConversationContentMaxWidth = 760.dp

internal const val CONVERSATION_QUICK_JUMP_RAIL_TEST_TAG = "conversation-quick-jump-rail"
internal const val CONVERSATION_QUICK_JUMP_PREVIEW_TEST_TAG = "conversation-quick-jump-preview"

private const val QuickJumpRestingWidthDp = 9f
private const val QuickJumpPreviewPromptMaxChars = 400
private const val QuickJumpPreviewResponseMaxChars = 1_200

internal data class ConversationQuickJumpPreview(
    val prompt: String,
    val response: String,
)

internal fun usesWideConversationLayout(availableWidth: Dp): Boolean =
    availableWidth >= WideConversationBreakpoint

internal fun conversationHorizontalInset(availableWidth: Dp): Dp =
    if (usesWideConversationLayout(availableWidth)) {
        ((availableWidth - ConversationContentMaxWidth) / 2).coerceAtLeast(0.dp)
    } else {
        0.dp
    }

/** One jump target for the first prompt in each consecutive run of user messages. */
internal fun conversationQuickJumpTargets(
    groups: List<TimelineRenderGroup>,
): List<Int> {
    var insideUserRun = false
    return buildList {
        groups.forEachIndexed { index, group ->
            when {
                group is TimelineRenderGroup.Single && group.item is TimelineItem.UserItem -> {
                    if (!insideUserRun) add(index)
                    insideUserRun = true
                }
                group is TimelineRenderGroup.AssistantRun -> insideUserRun = false
                // Status rows do not split prompts queued before the assistant starts.
                else -> Unit
            }
        }
    }
}

internal fun conversationQuickJumpWindow(
    targets: List<Int>,
    activeTarget: Int,
    maxVisible: Int,
): List<Int> {
    if (targets.isEmpty() || maxVisible <= 0) return emptyList()
    if (targets.size <= maxVisible) return targets

    val activeIndex = targets.indexOf(activeTarget).coerceAtLeast(0)
    val start =
        (activeIndex - maxVisible / 2)
            .coerceIn(0, targets.size - maxVisible)
    return targets.subList(start, start + maxVisible)
}

internal fun conversationQuickJumpTargetAt(
    pointerY: Float,
    railHeight: Float,
    markerSpacing: Float,
    visibleTargets: List<Int>,
): Int? {
    if (visibleTargets.isEmpty() || railHeight <= 0f || markerSpacing <= 0f) return null

    val clusterHeight = (visibleTargets.size - 1) * markerSpacing
    val clusterTop = (railHeight - clusterHeight) / 2f
    val hitSlop = markerSpacing / 2f
    if (pointerY < clusterTop - hitSlop || pointerY > clusterTop + clusterHeight + hitSlop) {
        return null
    }
    val markerIndex =
        ((pointerY - clusterTop) / markerSpacing)
            .roundToInt()
            .coerceIn(visibleTargets.indices)
    return visibleTargets[markerIndex]
}

/**
 * Marker width in dp. At rest every marker is identical; hover applies a
 * local, dock-like magnification that fades out over the nearest neighbours.
 */
internal fun conversationQuickJumpMarkerWidthDp(
    markerIndex: Float,
    hoveredIndex: Float?,
    hoverProgress: Float,
): Float {
    if (hoveredIndex == null) return QuickJumpRestingWidthDp

    val distance = abs(markerIndex - hoveredIndex)
    val lowerDistance = floor(distance).toInt()
    val fraction = distance - lowerDistance
    fun expandedWidthAt(wholeDistance: Int): Float =
        when (wholeDistance) {
            0 -> 38f
            1 -> 29f
            2 -> 20f
            3 -> 14f
            else -> QuickJumpRestingWidthDp
        }

    val expandedWidth =
        expandedWidthAt(lowerDistance) +
            (expandedWidthAt(lowerDistance + 1) - expandedWidthAt(lowerDistance)) * fraction
    val progress = hoverProgress.coerceIn(0f, 1f)
    return QuickJumpRestingWidthDp + (expandedWidth - QuickJumpRestingWidthDp) * progress
}

/** Prompt and visible assistant response belonging to one quick-jump turn. */
internal fun conversationQuickJumpPreview(
    groups: List<TimelineRenderGroup>,
    target: Int,
): ConversationQuickJumpPreview? {
    if (target !in groups.indices) return null
    val nextTarget =
        conversationQuickJumpTargets(groups).firstOrNull { it > target } ?: groups.size
    val turnGroups = groups.subList(target, nextTarget)
    val prompt =
        turnGroups
            .mapNotNull { group ->
                ((group as? TimelineRenderGroup.Single)?.item as? TimelineItem.UserItem)
                    ?.text
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .joinToString("\n")
            .toConversationPreviewText(QuickJumpPreviewPromptMaxChars)
            .ifBlank { "…" }
    val response =
        turnGroups
            .filterIsInstance<TimelineRenderGroup.AssistantRun>()
            .flatMap { it.items }
            .filterIsInstance<TimelineItem.AssistantItem>()
            .flatMap { it.blocks }
            .filter { it.kind == BlockKind.Text && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text.trim() }
            .toConversationPreviewText(QuickJumpPreviewResponseMaxChars)
    return ConversationQuickJumpPreview(prompt = prompt, response = response)
}

private val previewHeadingPrefix = Regex("^\\s{0,3}#{1,6}\\s+")
private val previewBulletPrefix = Regex("^(\\s*)[-*+]\\s+")
private val previewLink = Regex("\\[([^]]+)]\\([^)]+\\)")

private fun String.toConversationPreviewText(maxChars: Int): String {
    val plain =
        lineSequence()
            .filterNot { it.trimStart().startsWith("```") }
            .joinToString("\n") { line ->
                line
                    .replace(previewHeadingPrefix, "")
                    .replace(previewBulletPrefix) { match -> "${match.groupValues[1]}• " }
            }
            .replace(previewLink) { it.groupValues[1] }
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .trim()
    return if (plain.length <= maxChars) plain else plain.take(maxChars).trimEnd() + "…"
}

/** Compact minimap-like rail used to jump between user turns on wide screens. */
@Composable
internal fun ConversationQuickJumpRail(
    targets: List<Int>,
    currentItemIndex: Int,
    totalItemsCount: Int,
    previewForTarget: (Int) -> ConversationQuickJumpPreview? = { null },
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (targets.isEmpty() || totalItemsCount < 1) return

    val activeTarget = targets.lastOrNull { it <= currentItemIndex } ?: targets.first()
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f)
    val activeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    val description = S.conversationQuickNavigation
    val markerSpacing = 10.dp
    val maxVisibleMarkers = 24
    var railSize by remember { mutableStateOf(IntSize(0, 0)) }
    var hoveredTarget by remember(targets) { mutableStateOf<Int?>(null) }
    var lastHoveredIndex by remember(targets) { mutableStateOf(0) }
    val markerSpacingPx = with(LocalDensity.current) { markerSpacing.toPx() }
    val markerCapacity =
        ((railSize.height / markerSpacingPx).toInt() + 1)
            .coerceIn(2, maxVisibleMarkers)
    val visibleTargets =
        conversationQuickJumpWindow(
            targets = targets,
            activeTarget = activeTarget,
            maxVisible = markerCapacity,
        )
    val hoveredIndex = visibleTargets.indexOf(hoveredTarget).takeIf { it >= 0 }
    LaunchedEffect(visibleTargets, hoveredTarget) {
        if (hoveredTarget != null && hoveredIndex == null) hoveredTarget = null
    }
    val hoverProgress by
        animateFloatAsState(
            targetValue = if (hoveredIndex == null) 0f else 1f,
            animationSpec = tween(durationMillis = 120),
        )
    val animatedHoveredIndex =
        if (hoveredIndex != null) {
            // Re-entering the rail starts directly at the pointer; movement
            // between neighbouring markers then glides the magnified cluster.
            val value by
                animateFloatAsState(
                    targetValue = lastHoveredIndex.toFloat(),
                    animationSpec = tween(durationMillis = 90),
                )
            value
        } else {
            lastHoveredIndex.toFloat()
        }

    fun updateHoveredTarget(pointerY: Float) {
        val target =
            conversationQuickJumpTargetAt(
                pointerY = pointerY,
                railHeight = railSize.height.toFloat(),
                markerSpacing = markerSpacingPx,
                visibleTargets = visibleTargets,
            )
        hoveredTarget = target
        if (target != null) {
            val index = visibleTargets.indexOf(target)
            if (index >= 0) lastHoveredIndex = index
        }
    }

    Box(
        modifier =
            modifier
                .testTag(CONVERSATION_QUICK_JUMP_RAIL_TEST_TAG)
                .semantics { contentDescription = description }
                .onSizeChanged { railSize = it }
                .pointerInput(visibleTargets, railSize, markerSpacingPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Exit -> hoveredTarget = null
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    if (event.changes.none { it.pressed }) {
                                        event.changes.firstOrNull()?.let { change ->
                                            updateHoveredTarget(change.position.y)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .pointerInput(visibleTargets, railSize, markerSpacingPx, totalItemsCount) {
                    detectTapGestures { position ->
                        conversationQuickJumpTargetAt(
                            pointerY = position.y,
                            railHeight = railSize.height.toFloat(),
                            markerSpacing = markerSpacingPx,
                            visibleTargets = visibleTargets,
                        )?.let(onJump)
                    }
                },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val markerHeight = 2.dp.toPx()
            val clusterHeight = (visibleTargets.size - 1) * markerSpacingPx
            val clusterTop = (size.height - clusterHeight) / 2f

            visibleTargets.forEachIndexed { markerIndex, target ->
                val active = target == activeTarget
                val markerWidth =
                    conversationQuickJumpMarkerWidthDp(
                        markerIndex = markerIndex.toFloat(),
                        hoveredIndex = animatedHoveredIndex.takeIf { hoverProgress > 0f },
                        hoverProgress = hoverProgress,
                    ).dp.toPx()
                val markerCenterY = clusterTop + markerIndex * markerSpacingPx
                val y = markerCenterY - markerHeight / 2f
                drawRoundRect(
                    color = if (active) activeColor else inactiveColor,
                    topLeft = Offset((size.width - markerWidth) / 2f, y),
                    size = Size(markerWidth, markerHeight),
                    cornerRadius = CornerRadius(markerHeight / 2f),
                )
            }
        }

        val preview = hoveredTarget?.let(previewForTarget)
        if (preview != null && hoveredIndex != null) {
            val clusterHeight = (visibleTargets.size - 1) * markerSpacingPx
            val clusterTop = (railSize.height - clusterHeight) / 2f
            val markerCenterY = (clusterTop + hoveredIndex * markerSpacingPx).roundToInt()
            val density = LocalDensity.current
            val positionProvider =
                remember(markerCenterY, density) {
                    ConversationQuickJumpPopupPositionProvider(
                        markerCenterY = markerCenterY,
                        horizontalGap = with(density) { 14.dp.roundToPx() },
                        windowMargin = with(density) { 12.dp.roundToPx() },
                    )
                }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { hoveredTarget = null },
            ) {
                ConversationQuickJumpPreviewCard(preview)
            }
        }
    }
}

private class ConversationQuickJumpPopupPositionProvider(
    private val markerCenterY: Int,
    private val horizontalGap: Int,
    private val windowMargin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = anchorBounds.right + horizontalGap
        val left = anchorBounds.left - horizontalGap - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width - windowMargin).coerceAtLeast(windowMargin)
        val x =
            when {
                right <= maxX -> right
                left >= windowMargin -> left
                else -> right.coerceIn(windowMargin, maxX)
            }
        val desiredY = anchorBounds.top + markerCenterY - popupContentSize.height / 2
        val maxY = (windowSize.height - popupContentSize.height - windowMargin).coerceAtLeast(windowMargin)
        return IntOffset(x, desiredY.coerceIn(windowMargin, maxY))
    }
}

@Composable
private fun ConversationQuickJumpPreviewCard(preview: ConversationQuickJumpPreview) {
    Surface(
        modifier = Modifier.width(480.dp).testTag(CONVERSATION_QUICK_JUMP_PREVIEW_TEST_TAG),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = preview.prompt,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.response.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = preview.response,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

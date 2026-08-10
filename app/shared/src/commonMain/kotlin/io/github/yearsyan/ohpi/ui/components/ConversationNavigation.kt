package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.TimelineRenderGroup
import io.github.yearsyan.ohpi.i18n.S
import kotlin.math.roundToInt

/** Width at which the transcript has enough spare room for side navigation. */
internal val WideConversationBreakpoint = 900.dp

/** Keeps long desktop/tablet lines readable while compact layouts continue to use all available width. */
internal val ConversationContentMaxWidth = 760.dp

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

/** Compact minimap-like rail used to jump between user turns on wide screens. */
@Composable
internal fun ConversationQuickJumpRail(
    targets: List<Int>,
    currentItemIndex: Int,
    totalItemsCount: Int,
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

    Canvas(
        modifier =
            modifier
                .semantics { contentDescription = description }
                .pointerInput(targets, totalItemsCount, activeTarget) {
                    detectTapGestures { position ->
                        val spacingPx = markerSpacing.toPx()
                        val markerCapacity =
                            ((size.height / spacingPx).toInt() + 1)
                                .coerceIn(2, maxVisibleMarkers)
                        val visibleTargets =
                            conversationQuickJumpWindow(
                                targets = targets,
                                activeTarget = activeTarget,
                                maxVisible = markerCapacity,
                            )
                        conversationQuickJumpTargetAt(
                            pointerY = position.y,
                            railHeight = size.height.toFloat(),
                            markerSpacing = spacingPx,
                            visibleTargets = visibleTargets,
                        )?.let(onJump)
                    }
                },
    ) {
        val inactiveWidth = 9.dp.toPx()
        val activeWidth = 23.dp.toPx()
        val inactiveHeight = 2.dp.toPx()
        val activeHeight = 3.dp.toPx()
        val spacingPx = markerSpacing.toPx()
        val markerCapacity =
            ((size.height / spacingPx).toInt() + 1)
                .coerceIn(2, maxVisibleMarkers)
        val visibleTargets =
            conversationQuickJumpWindow(
                targets = targets,
                activeTarget = activeTarget,
                maxVisible = markerCapacity,
            )
        val clusterHeight = (visibleTargets.size - 1) * spacingPx
        val clusterTop = (size.height - clusterHeight) / 2f

        visibleTargets.forEachIndexed { markerIndex, target ->
            val active = target == activeTarget
            val markerWidth =
                when {
                    active -> activeWidth
                    else -> inactiveWidth
                }
            val markerHeight = if (active) activeHeight else inactiveHeight
            val markerCenterY = clusterTop + markerIndex * spacingPx
            val y = markerCenterY - markerHeight / 2f
            drawRoundRect(
                color = if (active) activeColor else inactiveColor,
                topLeft = Offset((size.width - markerWidth) / 2f, y),
                size = Size(markerWidth, markerHeight),
                cornerRadius = CornerRadius(markerHeight / 2f),
            )
        }
    }
}

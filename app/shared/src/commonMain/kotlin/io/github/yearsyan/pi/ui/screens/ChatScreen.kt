package io.github.yearsyan.pi.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.TimelineItem
import io.github.yearsyan.pi.chat.TimelineRenderGroup
import io.github.yearsyan.pi.chat.groupTimelineItems
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.ui.components.AssistantRunRow
import io.github.yearsyan.pi.ui.components.ChatTopBar
import io.github.yearsyan.pi.ui.components.Composer
import io.github.yearsyan.pi.ui.components.ConfirmDialog
import io.github.yearsyan.pi.ui.components.ExtensionDialog
import io.github.yearsyan.pi.ui.components.RenameDialog
import io.github.yearsyan.pi.ui.components.StatusLine
import io.github.yearsyan.pi.ui.components.UserMessageRow
import io.github.yearsyan.pi.ui.components.animateScrollToBottom
import io.github.yearsyan.pi.ui.components.scrollToBottom
import kotlinx.coroutines.launch

private enum class ChatBodyState {
    Loading,
    Empty,
    Messages,
}

/** Full chat pane: top bar, message timeline, composer, dialogs. */
@Composable
fun ChatScreen(
    controller: ChatController,
    showBack: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onBrowseFiles: (String) -> Unit = {},
) {
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var scrollToBottomTick by remember { mutableIntStateOf(0) }
    val composerBottomPadding = with(LocalDensity.current) { composerHeightPx.toDp() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopBar(
            controller = controller,
            showBack = showBack,
            onBack = onBack,
            onRename = { renameOpen = true },
            onDelete = { deleteOpen = true },
            onBrowseFiles = { onBrowseFiles(controller.workDir) },
        )

        val bodyState = when {
            controller.items.isNotEmpty() -> ChatBodyState.Messages
            controller.conn == ConnState.Connecting || controller.isLoadingHistory -> ChatBodyState.Loading
            else -> ChatBodyState.Empty
        }
        Box(Modifier.weight(1f)) {
            Crossfade(
                targetState = bodyState,
                modifier = Modifier.fillMaxSize(),
                animationSpec = tween(durationMillis = 180),
                label = "chatBody",
            ) { state ->
                when (state) {
                    ChatBodyState.Loading -> SessionLoadingState(Modifier.fillMaxSize())
                    ChatBodyState.Empty -> EmptyChatState(Modifier.fillMaxSize())
                    ChatBodyState.Messages ->
                        MessageList(
                            controller = controller,
                            bottomPadding = composerBottomPadding,
                            scrollToBottomTick = scrollToBottomTick,
                            modifier = Modifier.fillMaxSize(),
                        )
                }
            }

            Composer(
                controller = controller,
                onPromptSent = { scrollToBottomTick++ },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { composerHeightPx = it.height },
            )
        }
    }

    if (renameOpen) {
        RenameDialog(
            initial = controller.sessionName,
            onDismiss = { renameOpen = false },
            onConfirm = onRename,
        )
    }
    if (deleteOpen) {
        ConfirmDialog(
            title = S.deleteSessionTitle,
            body = S.deleteSessionBody,
            onDismiss = { deleteOpen = false },
            onConfirm = onDelete,
        )
    }
    controller.dialog?.let { req ->
        ExtensionDialog(request = req, onRespond = controller::respondDialog)
    }
}

@Composable
private fun SessionLoadingState(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sessionLoading")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sessionLoadingPulse",
    )
    val skeletonColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            S.connecting,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.8f),
        )
        Spacer(Modifier.height(5.dp))
        LoadingBar(0.78f, 14.dp, skeletonColor, pulse)
        LoadingBar(0.58f, 14.dp, skeletonColor, pulse * 0.9f)
        LoadingBar(0.68f, 14.dp, skeletonColor, pulse * 0.8f)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.56f)
                    .height(48.dp)
                    .alpha(pulse * 0.9f)
                    .background(skeletonColor, RoundedCornerShape(18.dp)),
            )
        }
        Spacer(Modifier.height(12.dp))
        LoadingBar(0.72f, 14.dp, skeletonColor, pulse * 0.85f)
        LoadingBar(0.46f, 14.dp, skeletonColor, pulse * 0.75f)
    }
}

@Composable
private fun LoadingBar(
    widthFraction: Float,
    height: Dp,
    color: Color,
    alpha: Float,
) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .alpha(alpha)
            .background(color, RoundedCornerShape(7.dp)),
    )
}

@Composable
private fun MessageList(
    controller: ChatController,
    bottomPadding: Dp,
    scrollToBottomTick: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var pinned by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val renderGroups = groupTimelineItems(controller.items)
    val lastAssistantRunKey =
        renderGroups.lastOrNull { it is TimelineRenderGroup.AssistantRun }?.key

    // Track whether the user is near the bottom. The last render group is a
    // single item that can be taller than the viewport (a long assistant run),
    // so index proximity is not enough: require the tail of the last item to be
    // within a small distance of the viewport's content end.
    val pinnedThresholdPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow true
            if (info.totalItemsCount == 0) return@snapshotFlow true
            if (last.index < info.totalItemsCount - 1) return@snapshotFlow false
            val contentEnd = info.viewportEndOffset - info.afterContentPadding
            last.offset + last.size <= contentEnd + pinnedThresholdPx
        }.collect { pinned = it }
    }

    // auto-scroll when pinned and new content arrives
    val itemCount = renderGroups.size
    LaunchedEffect(itemCount) {
        if (pinned && itemCount > 0) listState.scrollToBottom(itemCount - 1)
    }

    // jump to the tail after the user sends a prompt, even when scrolled up
    LaunchedEffect(scrollToBottomTick) {
        if (scrollToBottomTick > 0 && renderGroups.isNotEmpty()) {
            pinned = true
            listState.scrollToBottom(renderGroups.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding + 10.dp),
        ) {
            items(renderGroups, key = { it.key }) { group ->
                when (group) {
                    is TimelineRenderGroup.AssistantRun ->
                        AssistantRunRow(
                            items = group.items,
                            isStreaming =
                                group.items.any { it is TimelineItem.AssistantItem && it.streaming } ||
                                    (controller.isStreaming && group.key == lastAssistantRunKey),
                        )
                    is TimelineRenderGroup.Single -> {
                        when (val item = group.item) {
                            is TimelineItem.UserItem -> UserMessageRow(item)
                            is TimelineItem.StatusItem -> StatusLine(item)
                            is TimelineItem.AssistantItem, is TimelineItem.ToolItem -> Unit
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !pinned,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPadding + 14.dp),
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f, animationSpec = tween(150)),
        ) {
            Surface(
                onClick = {
                    pinned = true
                    scope.launch { listState.animateScrollToBottom(renderGroups.lastIndex) }
                },
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = S.scrollToBottom,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // keep the tail visible while streaming content grows; the tail signature
    // changes on new blocks and on every text/output delta of the last item
    val tailItem = controller.items.lastOrNull()
    val tailSignature: Any? =
        when (tailItem) {
            is TimelineItem.AssistantItem ->
                Triple(
                    tailItem.blocks.size,
                    tailItem.blocks.lastOrNull()?.text?.length ?: 0,
                    tailItem.blocks.lastOrNull()?.tool?.output?.length ?: 0,
                )
            is TimelineItem.ToolItem -> tailItem.tool.output.length
            else -> tailItem?.key
        }
    LaunchedEffect(tailSignature) {
        if (pinned && controller.items.isNotEmpty()) {
            listState.scrollToBottom(renderGroups.lastIndex)
        }
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "π",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            S.emptyChatTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            S.emptyChatBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

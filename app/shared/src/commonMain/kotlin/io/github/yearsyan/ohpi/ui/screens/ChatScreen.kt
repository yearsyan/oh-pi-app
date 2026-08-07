package io.github.yearsyan.ohpi.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.TimelineRenderGroup
import io.github.yearsyan.ohpi.chat.groupTimelineItems
import io.github.yearsyan.ohpi.data.ConnState
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.ui.components.AssistantRunRow
import io.github.yearsyan.ohpi.ui.components.ChatTopBar
import io.github.yearsyan.ohpi.ui.components.Composer
import io.github.yearsyan.ohpi.ui.components.ConfirmDialog
import io.github.yearsyan.ohpi.ui.components.ExtensionDialog
import io.github.yearsyan.ohpi.ui.components.KeepScreenOn
import io.github.yearsyan.ohpi.ui.components.RenameDialog
import io.github.yearsyan.ohpi.ui.components.StatusLine
import io.github.yearsyan.ohpi.ui.components.StreamingCaret
import io.github.yearsyan.ohpi.ui.components.UserMessageRow
import io.github.yearsyan.ohpi.ui.components.animateScrollToBottom
import io.github.yearsyan.ohpi.ui.components.localizedLabel
import io.github.yearsyan.ohpi.ui.components.requestScrollToBottom
import io.github.yearsyan.ohpi.ui.components.resolveSessionStatus
import io.github.yearsyan.ohpi.ui.components.scrollToBottom
import kotlinx.coroutines.launch

private enum class ChatBodyState {
    Loading,
    Empty,
    NoModel,
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
    onStopProcess: (() -> Unit)? = null,
    onBrowseFiles: (String) -> Unit = {},
    onOpenProviders: () -> Unit = {},
) {
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var stopProcessOpen by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var scrollToBottomTick by remember { mutableIntStateOf(0) }
    val composerBottomPadding = with(LocalDensity.current) { composerHeightPx.toDp() }

    // Keep the display awake while the model is generating output.
    KeepScreenOn(controller.isStreaming)

    // A fresh draft with no usable model can only fail on submit; take the
    // user straight to provider management, once per draft.
    LaunchedEffect(controller, controller.missingModel) {
        if (controller.isDraft && controller.missingModel && !controller.providerSetupNavigated) {
            controller.markProviderSetupNavigated()
            onOpenProviders()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopBar(
            controller = controller,
            showBack = showBack,
            onBack = onBack,
            onRename = { renameOpen = true },
            onDelete = { deleteOpen = true },
            onStopProcess = onStopProcess?.let { { stopProcessOpen = true } },
            onBrowseFiles = { onBrowseFiles(controller.workDir) },
        )

        val bodyState = when {
            controller.items.isNotEmpty() -> ChatBodyState.Messages
            controller.conn == ConnState.Connecting || controller.isLoadingHistory -> ChatBodyState.Loading
            controller.missingModel -> ChatBodyState.NoModel
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
                    ChatBodyState.Loading -> SessionLoadingState(controller, Modifier.fillMaxSize())
                    ChatBodyState.Empty -> EmptyChatState(Modifier.fillMaxSize())
                    ChatBodyState.NoModel ->
                        NoModelChatState(onOpenProviders, Modifier.fillMaxSize())
                    ChatBodyState.Messages ->
                        MessageList(
                            controller = controller,
                            bottomPadding = composerBottomPadding,
                            scrollToBottomTick = scrollToBottomTick,
                            modifier = Modifier.fillMaxSize(),
                        )
                }
            }

            if (controller.missingModel) {
                NoModelComposerBar(
                    onOpenProviders = onOpenProviders,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { composerHeightPx = it.height },
                )
            } else {
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
            confirmLabel = S.delete,
            onDismiss = { deleteOpen = false },
            onConfirm = onDelete,
        )
    }
    if (stopProcessOpen && onStopProcess != null) {
        ConfirmDialog(
            title = S.stopPiProcessTitle,
            body = S.stopPiProcessBody,
            confirmLabel = S.stopPiProcess,
            onDismiss = { stopProcessOpen = false },
            onConfirm = onStopProcess,
        )
    }
    controller.dialog?.let { req ->
        ExtensionDialog(request = req, onRespond = controller::respondDialog)
    }
}

@Composable
private fun SessionLoadingState(
    controller: ChatController,
    modifier: Modifier = Modifier,
) {
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
            resolveSessionStatus(controller.conn, controller.syncPhase)
                .localizedLabel(controller.syncProgress),
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
internal fun MessageList(
    controller: ChatController,
    bottomPadding: Dp,
    scrollToBottomTick: Int,
    modifier: Modifier = Modifier,
) {
    // Each conversation gets its own list state: entering or switching to a
    // session starts at the tail instead of inheriting the previous
    // session's scroll position.
    val listState = key(controller) { rememberLazyListState() }
    val focusManager = LocalFocusManager.current
    // Once a user gesture moves the list, automatic tail following stays off
    // until the list reaches the exact bottom again. A near-bottom threshold
    // is useful for button visibility, but is too aggressive for this gate:
    // it can re-enable following while the first drag is still in progress.
    var userScrolledAway by remember(controller) { mutableStateOf(false) }
    var initialPositionPending by remember(controller) { mutableStateOf(true) }
    val dismissKeyboardOnScroll =
        remember(controller, focusManager, listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        focusManager.clearFocus()
                        userScrolledAway = true
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && !listState.canScrollForward) {
                        userScrolledAway = false
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (!listState.canScrollForward) userScrolledAway = false
                    return Velocity.Zero
                }
            }
        }
    var pinned by remember(controller) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val renderGroups = groupTimelineItems(controller.items)
    val lastGroupKey = renderGroups.lastOrNull()?.key
    val anyRunStreaming = renderGroups.any { group ->
        group is TimelineRenderGroup.AssistantRun &&
            group.items.any { it is TimelineItem.AssistantItem && it.streaming }
    }
    // While streaming with no live assistant run (e.g. right after the user
    // submits a prompt, before the first assistant block arrives), the caret
    // belongs under the last group — pinning it to the previous run would draw
    // it above the just-sent user message.
    val caretUnderLastGroup = controller.isStreaming && !anyRunStreaming

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

    val followingTail = !userScrolledAway

    val itemCount = renderGroups.size

    // the composer height (including multiline growth) changes the bottom
    // content padding after entry; adjust only when no gesture/scroll is active
    LaunchedEffect(controller, bottomPadding) {
        if (bottomPadding > 0.dp &&
            followingTail &&
            !listState.isScrollInProgress &&
            itemCount > 0
        ) {
            listState.requestScrollToBottom(itemCount - 1)
        }
    }

    // jump to the tail after the user sends a prompt, even when scrolled up
    LaunchedEffect(scrollToBottomTick) {
        if (scrollToBottomTick > 0 && renderGroups.isNotEmpty()) {
            pinned = true
            userScrolledAway = false
            initialPositionPending = false
            listState.requestScrollToBottom(renderGroups.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(dismissKeyboardOnScroll),
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
                                    (caretUnderLastGroup && group.key == lastGroupKey),
                            onProcessDetailsToggled = { userScrolledAway = true },
                        )
                    is TimelineRenderGroup.Single -> {
                        when (val item = group.item) {
                            is TimelineItem.UserItem -> UserMessageRow(item)
                            is TimelineItem.StatusItem -> StatusLine(item)
                            is TimelineItem.AssistantItem, is TimelineItem.ToolItem -> Unit
                        }
                        if (caretUnderLastGroup && group.key == lastGroupKey) {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                StreamingCaret()
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = userScrolledAway && listState.canScrollForward,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = bottomPadding + 16.dp),
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f, animationSpec = tween(150)),
        ) {
            Surface(
                onClick = {
                    pinned = true
                    userScrolledAway = false
                    scope.launch { listState.animateScrollToBottom(renderGroups.lastIndex) }
                },
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

    // Position a newly entered conversation once. Subsequent streaming updates
    // follow only while the list remains near the bottom and no scroll is in
    // progress; a synchronous request on every delta would fight user input.
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
    LaunchedEffect(controller, tailSignature) {
        if (controller.items.isEmpty()) return@LaunchedEffect
        if (initialPositionPending) {
            initialPositionPending = false
            listState.requestScrollToBottom(renderGroups.lastIndex)
        } else if (followingTail && pinned && !listState.isScrollInProgress) {
            listState.scrollToBottom(renderGroups.lastIndex)
        }
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    ChatPlaceholder(
        title = S.emptyChatTitle,
        body = S.emptyChatBody,
        actionLabel = null,
        onAction = {},
        modifier = modifier,
    )
}

@Composable
private fun NoModelChatState(
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatPlaceholder(
        title = S.noModelConfiguredTitle,
        body = S.noModelConfiguredBody,
        actionLabel = S.noModelConfigureAction,
        onAction = onOpenProviders,
        modifier = modifier,
    )
}

@Composable
private fun ChatPlaceholder(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Composer replacement shown while no provider/model is configured. */
@Composable
private fun NoModelComposerBar(
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().imePadding()) {
        // Same floating-card treatment as the regular composer.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 36.dp)
                .background(MaterialTheme.colorScheme.background),
        )
        val darkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = if (darkScheme) 0.dp else 4.dp,
            border = if (darkScheme) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    S.noModelConfiguredTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onOpenProviders) {
                    Text(S.noModelConfigureAction, maxLines = 1)
                }
            }
        }
    }
}

package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.yearsyan.ohpi.chat.AssistantRenderChunk
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.TimelineRenderGroup
import io.github.yearsyan.ohpi.chat.chunkAssistantRun
import io.github.yearsyan.ohpi.chat.groupTimelineItems
import io.github.yearsyan.ohpi.data.ConnState
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.markdown.MarkdownParseCache
import io.github.yearsyan.ohpi.ui.components.AssistantRunRow
import io.github.yearsyan.ohpi.ui.components.ChatTopBar
import io.github.yearsyan.ohpi.ui.components.Composer
import io.github.yearsyan.ohpi.ui.components.ConversationContentMaxWidth
import io.github.yearsyan.ohpi.ui.components.ConversationQuickJumpRail
import io.github.yearsyan.ohpi.ui.components.ConfirmDialog
import io.github.yearsyan.ohpi.ui.components.ExtensionDialog
import io.github.yearsyan.ohpi.ui.components.FollowScrollPacer
import io.github.yearsyan.ohpi.ui.components.KeepScreenOn
import io.github.yearsyan.ohpi.ui.components.PlatformScrollToBottomButton
import io.github.yearsyan.ohpi.ui.components.RenameDialog
import io.github.yearsyan.ohpi.ui.components.StatusLine
import io.github.yearsyan.ohpi.ui.components.StreamingCaret
import io.github.yearsyan.ohpi.ui.components.UserMessageRow
import io.github.yearsyan.ohpi.ui.components.animateScrollToBottom
import io.github.yearsyan.ohpi.ui.components.compensateVisibleTailToBottom
import io.github.yearsyan.ohpi.ui.components.conversationHorizontalInset
import io.github.yearsyan.ohpi.ui.components.conversationQuickJumpPreview
import io.github.yearsyan.ohpi.ui.components.conversationQuickJumpTargets
import io.github.yearsyan.ohpi.ui.components.isWithinBottomThreshold
import io.github.yearsyan.ohpi.ui.components.localizedLabel
import io.github.yearsyan.ohpi.ui.components.platformSupportsComposerBackdropBlur
import io.github.yearsyan.ohpi.ui.components.requestScrollToBottom
import io.github.yearsyan.ohpi.ui.components.resolveSessionStatus
import io.github.yearsyan.ohpi.ui.components.scrollToBottom
import io.github.yearsyan.ohpi.ui.components.usesWideConversationLayout
import io.github.yearsyan.ohpi.ui.privacy.AiDataConsentRequest
import io.github.yearsyan.ohpi.ui.privacy.rememberAiDataConsentPresenter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private enum class ChatBodyState {
    Loading,
    Empty,
    NoModel,
    Messages,
}

private class MessageListGestureState {
    var keyboardDismissRequested = false
    var tailSignatureAtDown: TailFollowSignature? = null
}

private val BottomAttachmentThreshold = 16.dp
private val StreamingTailFollowThreshold = 96.dp

/** Full chat pane: top bar, message timeline, composer, dialogs. */
@Composable
fun ChatScreen(
    controller: ChatController,
    serverId: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onStopProcess: (() -> Unit)? = null,
    onBrowseFiles: (String) -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onLoadToolImage: (suspend (String) -> ByteArray)? = null,
) {
    val strings = S
    val aiDataConsentPresenter = rememberAiDataConsentPresenter()
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var stopProcessOpen by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var scrollToBottomTick by remember { mutableIntStateOf(0) }
    var keyboardDismissTick by remember(controller) { mutableIntStateOf(0) }
    val composerBottomPadding = with(LocalDensity.current) { composerHeightPx.toDp() }
    val composerBackdropState =
        if (platformSupportsComposerBackdropBlur) rememberHazeState() else null

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
        val activeComposerBackdropState =
            composerBackdropState?.takeUnless { controller.missingModel }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wideConversationLayout = usesWideConversationLayout(maxWidth)
            val centeredComposerModifier =
                if (wideConversationLayout) {
                    Modifier.widthIn(max = ConversationContentMaxWidth)
                } else {
                    Modifier
                }
            Crossfade(
                targetState = bodyState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (activeComposerBackdropState != null) {
                                Modifier.hazeSource(state = activeComposerBackdropState)
                            } else {
                                Modifier
                            },
                        ),
                animationSpec = tween(durationMillis = 180),
                label = "chatBody",
            ) { state ->
                when (state) {
                    ChatBodyState.Loading ->
                        SessionLoadingState(
                            controller = controller,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        horizontal = conversationHorizontalInset(maxWidth),
                                    ),
                        )
                    ChatBodyState.Empty -> EmptyChatState(Modifier.fillMaxSize())
                    ChatBodyState.NoModel ->
                        NoModelChatState(onOpenProviders, Modifier.fillMaxSize())
                    ChatBodyState.Messages ->
                        MessageList(
                            controller = controller,
                            bottomPadding = composerBottomPadding,
                            scrollToBottomTick = scrollToBottomTick,
                            modifier = Modifier.fillMaxSize(),
                            onLoadToolImage = onLoadToolImage,
                            onKeyboardDismissRequested = { keyboardDismissTick++ },
                        )
                }
            }

            if (controller.missingModel) {
                NoModelComposerBar(
                    onOpenProviders = onOpenProviders,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(centeredComposerModifier)
                            .onSizeChanged { composerHeightPx = it.height },
                )
            } else {
                Composer(
                    controller = controller,
                    backdropState = activeComposerBackdropState,
                    keyboardDismissTick = keyboardDismissTick,
                    onPromptSent = { scrollToBottomTick++ },
                    onSubmitInput = { text, images ->
                        val model = controller.currentModel
                        val providerId =
                            model?.provider?.trim().orEmpty().ifBlank {
                                model?.qualified ?: controller.model.ifBlank { "unknown" }
                            }
                        val providerName =
                            model?.provider?.trim().orEmpty()
                                .ifBlank { strings.aiDataConsentUnknownProvider }
                        val modelName =
                            model?.label ?: controller.model.ifBlank { providerName }
                        aiDataConsentPresenter.requestConsent(
                            request =
                                AiDataConsentRequest(
                                    serverId = serverId,
                                    providerId = providerId,
                                    title = strings.aiDataConsentTitle(providerName),
                                    message = strings.aiDataConsentMessage(providerName, modelName),
                                    cancelLabel = strings.cancel,
                                    privacyPolicyLabel = strings.privacyPolicy,
                                    agreeAndSendLabel = strings.aiDataConsentAgreeAndSend,
                                ),
                            onGranted = { controller.submitInput(text, images) },
                        )
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(centeredComposerModifier)
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
        ExtensionDialog(
            request = req,
            onRespond = { response -> controller.respondDialog(req.id, response) },
        )
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

@OptIn(FlowPreview::class)
@Composable
internal fun MessageList(
    controller: ChatController,
    bottomPadding: Dp,
    scrollToBottomTick: Int,
    listStateOverride: LazyListState? = null,
    modifier: Modifier = Modifier,
    onLoadToolImage: (suspend (String) -> ByteArray)? = null,
    onKeyboardDismissRequested: () -> Unit = {},
) {
    // Each conversation gets its own list state: entering or switching to a
    // session starts at the tail instead of inheriting the previous
    // session's scroll position.
    val rememberedListState = key(controller) { rememberLazyListState() }
    val listState = listStateOverride ?: rememberedListState
    val focusManager = LocalFocusManager.current
    val currentOnKeyboardDismissRequested by rememberUpdatedState(onKeyboardDismissRequested)
    val density = LocalDensity.current
    val bottomAttachmentThresholdPx =
        with(density) { BottomAttachmentThreshold.roundToPx() }
    // Once a user gesture moves beyond the 16dp attachment zone, automatic
    // tail following stays off until the list re-enters that zone.
    var userScrolledAway by remember(controller) { mutableStateOf(false) }
    var bottomAttached by remember(controller) { mutableStateOf(true) }
    var initialPositionPending by remember(controller) { mutableStateOf(true) }
    var bottomJumpAnimating by remember(controller) { mutableStateOf(false) }
    // Expanding any process block in the final assistant run from within the
    // 16dp attachment zone first closes that small gap, then uses the block's
    // bottom edge as its anchor. The tail-height watcher below compensates every
    // animation step, so the details grow upward. Starting farther away keeps
    // the normal top-edge anchor instead.
    var tailProcessBottomAnchored by remember(controller) { mutableStateOf(false) }
    val gestureState = remember(controller) { MessageListGestureState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState, bottomAttachmentThresholdPx) {
        snapshotFlow { listState.isWithinBottomThreshold(bottomAttachmentThresholdPx) }
            .collect { attached ->
                bottomAttached = attached
                if (attached) userScrolledAway = false
            }
    }
    val dismissKeyboardOnScroll =
        remember(controller, focusManager, listState, bottomAttachmentThresholdPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        if (!gestureState.keyboardDismissRequested) {
                            gestureState.keyboardDismissRequested = true
                            // The iOS 26 Liquid Glass composer is hosted in a
                            // child ComposeUIView with its own focus manager.
                            currentOnKeyboardDismissRequested()
                        }
                        focusManager.clearFocus()
                        userScrolledAway = true
                        tailProcessBottomAnchored = false
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        val attached =
                            listState.isWithinBottomThreshold(bottomAttachmentThresholdPx)
                        bottomAttached = attached
                        userScrolledAway = !attached
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    val attached = listState.isWithinBottomThreshold(bottomAttachmentThresholdPx)
                    bottomAttached = attached
                    userScrolledAway = !attached
                    return Velocity.Zero
                }
            }
        }
    var pinned by remember(controller) { mutableStateOf(true) }
    // While a finger rests on the list, tail-following must not shift rows out
    // from under it. A programmatic scroll landing between pointer-down and
    // -up moves the pressed row away from the finger, and the tap detector
    // then reports the release as out-of-bounds — the click never fires.
    // That made thinking/tool headers unexpandable while the pinned tail was
    // actively streaming, so auto-scrolls are paused during contact and the
    // list catches up once the finger lifts.
    var pointerInContact by remember(controller) { mutableStateOf(false) }
    var pointerReleaseTick by remember(controller) { mutableIntStateOf(0) }
    // Streaming deltas arrive far more often than one scroll is worth doing:
    // the pacer spaces tail-follow scrolls to at most one per window while
    // still guaranteeing the trailing scroll that pins the final delta.
    val followPacer = remember(controller) { FollowScrollPacer() }
    val renderGroups = groupTimelineItems(controller.items)
    val tailSignature = tailFollowSignature(renderGroups.lastOrNull())
    val currentTailSignature by rememberUpdatedState(tailSignature)
    val quickJumpTargets = remember(renderGroups) { conversationQuickJumpTargets(renderGroups) }

    // Pre-parse settled markdown blocks into MarkdownParseCache so scrolling a
    // recycled LazyColumn item into view renders synchronously instead of
    // flashing the renderer's zero-height async loading box. Streaming groups
    // are skipped while their text is still changing; only the exact settled
    // text is admitted to the shared cache. Warming is debounced so a burst of
    // timeline updates (streaming deltas, live block growth) does not compete
    // with the renderer's own parse for background threads mid-gesture.
    LaunchedEffect(controller) {
        snapshotFlow {
            renderGroups.flatMap { group ->
                when (group) {
                    is TimelineRenderGroup.AssistantRun ->
                        if (group.items.any { it is TimelineItem.AssistantItem && it.streaming }) {
                            emptyList()
                        } else {
                            chunkAssistantRun(group.items).mapNotNull { chunk ->
                                when (chunk) {
                                    is AssistantRenderChunk.Text -> chunk.block.text
                                    else -> null
                                }
                            }
                        }
                    is TimelineRenderGroup.Single -> emptyList()
                }
            }
        }.debounce(600).collect { texts ->
            texts.forEach { MarkdownParseCache.warm(it) }
        }
    }
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
    val streamingPinnedThresholdPx =
        with(LocalDensity.current) { StreamingTailFollowThreshold.roundToPx() }
    LaunchedEffect(listState, streamingPinnedThresholdPx) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow true
            if (info.totalItemsCount == 0) return@snapshotFlow true
            if (last.index < info.totalItemsCount - 1) return@snapshotFlow false
            val contentEnd = info.viewportEndOffset - info.afterContentPadding
            last.offset + last.size <= contentEnd + streamingPinnedThresholdPx
        }.collect { pinned = it }
    }

    val followingTail = !userScrolledAway

    val itemCount = renderGroups.size

    // The tail can gain height after it first enters composition. Markdown is
    // parsed asynchronously, so a jump from far away may initially measure the
    // final assistant run at (nearly) zero height. While tail following is
    // enabled, repeat the non-animated correction whenever the measured tail
    // height changes. Collapsed thinking/tool payload updates do not change the
    // measured height and therefore do not cause scroll requests.
    LaunchedEffect(
        listState,
        followingTail,
        itemCount,
        bottomJumpAnimating,
        tailProcessBottomAnchored,
    ) {
        if (!followingTail || itemCount == 0 || bottomJumpAnimating) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.firstOrNull { it.index == itemCount - 1 }
            TailLayoutSnapshot(
                totalItemsCount = info.totalItemsCount,
                measuredTailHeight = tail?.size,
                canScrollForward = listState.canScrollForward,
            )
        }.collect { layout ->
            if (layout.canScrollForward && !listState.isScrollInProgress && !pointerInContact) {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (tailProcessBottomAnchored) {
                    // Keep the tail's bottom at the same viewport coordinate
                    // by consuming only this frame's newly measured height.
                    // A direct delta avoids stacking final-item relocation
                    // requests throughout the expansion animation.
                    if (!userScrolledAway) {
                        listState.compensateVisibleTailToBottom()
                    }
                } else {
                    followPacer.awaitTurn()
                    // re-check after the throttle wait: a gesture may have started
                    if (!tailProcessBottomAnchored &&
                        !userScrolledAway && !pointerInContact &&
                        listState.canScrollForward && lastIndex >= 0
                    ) {
                        // This snapshot can be emitted from a measure pass when
                        // deferred Markdown changes the tail height. Queue the
                        // correction for the next layout instead of forcing a
                        // nested remeasure through scrollBy.
                        listState.requestScrollToBottom(lastIndex)
                    }
                }
            }
        }
    }

    // the composer height (including multiline growth) changes the bottom
    // content padding after entry; adjust only when no gesture/scroll is active
    LaunchedEffect(controller, bottomPadding) {
        if (bottomPadding > 0.dp &&
            followingTail &&
            !tailProcessBottomAnchored &&
            !listState.isScrollInProgress &&
            !pointerInContact &&
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

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wideConversationLayout = usesWideConversationLayout(maxWidth)
        val horizontalInset = conversationHorizontalInset(maxWidth)

        fun requestJumpToGroup(targetIndex: Int) {
            if (renderGroups.isEmpty()) return
            val target = targetIndex.coerceIn(0, renderGroups.lastIndex)
            val atBottom = target == renderGroups.lastIndex
            initialPositionPending = false
            bottomJumpAnimating = false
            tailProcessBottomAnchored = false
            userScrolledAway = !atBottom
            pinned = atBottom
            if (atBottom) {
                listState.requestScrollToBottom(renderGroups.lastIndex)
            } else {
                listState.requestScrollToItem(target)
            }
        }

        // The Android stretch overscroll wedges against the auto-following
        // tail (each programmatic scroll interrupts the edge effect's release
        // animation), and desktop shows no meaningful edge feedback either.
        // Only iOS keeps its native rubber band.
        val overscrollEffect =
            if (remember { getPlatform().isIos }) {
                rememberOverscrollEffect()
            } else {
                null
            }
        LazyColumn(
            state = listState,
            overscrollEffect = overscrollEffect,
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(dismissKeyboardOnScroll)
                    .pointerInput(controller) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            pointerInContact = true
                            gestureState.keyboardDismissRequested = false
                            gestureState.tailSignatureAtDown = currentTailSignature
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.fastAny { it.pressed })
                            } finally {
                                pointerInContact = false
                                gestureState.keyboardDismissRequested = false
                                pointerReleaseTick++
                            }
                        }
                    },
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding =
                PaddingValues(
                    start = horizontalInset,
                    top = 10.dp,
                    end = horizontalInset,
                    bottom = bottomPadding + 10.dp,
                ),
        ) {
            items(renderGroups, key = { it.key }) { group ->
                when (group) {
                    is TimelineRenderGroup.AssistantRun ->
                        AssistantRunRow(
                            items = group.items,
                            isStreaming =
                                group.items.any { it is TimelineItem.AssistantItem && it.streaming } ||
                                    (caretUnderLastGroup && group.key == lastGroupKey),
                            isTailRun = group.key == lastGroupKey,
                            onProcessDetailsToggled = { expanding, isTailRunProcess ->
                                val anchorBottom =
                                    expanding &&
                                        isTailRunProcess &&
                                        listState.isWithinBottomThreshold(bottomAttachmentThresholdPx)
                                tailProcessBottomAnchored = anchorBottom
                                if (anchorBottom) {
                                    // Close the at-most-16dp gap before the first
                                    // expansion frame. Later frames use the same
                                    // bounded compensation from the current anchor.
                                    listState.compensateVisibleTailToBottom()
                                }
                                // Only an expansion decides whether tail following
                                // should continue. Collapsing a bottom-anchored block
                                // must preserve the existing follow state; otherwise
                                // the next expansion grows down and only snaps back
                                // to the bottom after its animation completes.
                                if (expanding) userScrolledAway = !anchorBottom
                            },
                            onProcessDetailsExpanded = { isTailRunProcess ->
                                if (isTailRunProcess && tailProcessBottomAnchored) {
                                    // Finish on the exact bottom even if the last
                                    // animation frame and its layout notification race.
                                    listState.compensateVisibleTailToBottom()
                                    tailProcessBottomAnchored = false
                                }
                            },
                            onLoadToolImage = onLoadToolImage,
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

        if (wideConversationLayout) {
            ConversationQuickJumpRail(
                targets = quickJumpTargets,
                currentItemIndex = listState.firstVisibleItemIndex,
                totalItemsCount = renderGroups.size,
                previewForTarget = { target ->
                    conversationQuickJumpPreview(renderGroups, target)
                },
                onJump = ::requestJumpToGroup,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(48.dp)
                        .fillMaxHeight()
                        .padding(start = 8.dp, top = 28.dp, bottom = bottomPadding + 28.dp),
            )
        }

        PlatformScrollToBottomButton(
            visible = userScrolledAway && !bottomAttached,
            onClick = {
                // Animate only the user-initiated jump. Once it finishes,
                // the layout watcher handles deferred Markdown height
                // changes without stacking additional animations.
                val lastIndex = renderGroups.lastIndex
                pinned = true
                userScrolledAway = false
                bottomJumpAnimating = true
                scope.launch {
                    try {
                        listState.animateScrollToBottom(lastIndex)
                    } finally {
                        bottomJumpAnimating = false
                    }
                }
            },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = if (wideConversationLayout) horizontalInset + 18.dp else 18.dp,
                        bottom = bottomPadding + 16.dp,
                    ),
        )
    }

    // Position a newly entered conversation once. Subsequent streaming updates
    // follow only while the list remains near the bottom and no scroll is in
    // progress; a synchronous request on every delta would fight user input.
    LaunchedEffect(controller, tailSignature) {
        if (controller.items.isEmpty()) return@LaunchedEffect
        if (initialPositionPending) {
            initialPositionPending = false
            listState.requestScrollToBottom(renderGroups.lastIndex)
        } else if (
            followingTail &&
            pinned &&
            !tailProcessBottomAnchored &&
            !listState.isScrollInProgress &&
            !pointerInContact
        ) {
            followPacer.awaitTurn()
            // re-check after the throttle wait: the user may have scrolled away
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (!tailProcessBottomAnchored &&
                !userScrolledAway && pinned && !pointerInContact && lastIndex >= 0
            ) {
                listState.scrollToBottom(lastIndex)
            }
        }
    }

    // Catch up once the finger lifts: deltas that arrived during the press
    // were deliberately not followed, leaving the tail short of the bottom.
    LaunchedEffect(pointerReleaseTick) {
        val tailChangedDuringContact =
            pointerReleaseTick > 0 && gestureState.tailSignatureAtDown != tailSignature
        gestureState.tailSignatureAtDown = tailSignature
        if (
            tailChangedDuringContact &&
            !pointerInContact &&
            !tailProcessBottomAnchored &&
            followingTail &&
            pinned &&
            renderGroups.isNotEmpty() &&
            listState.canScrollForward
        ) {
            listState.scrollToBottom(renderGroups.lastIndex)
        }
    }
}

private data class TailLayoutSnapshot(
    val totalItemsCount: Int,
    val measuredTailHeight: Int?,
    val canScrollForward: Boolean,
)

/** Only changes when the rendered tail can grow; collapsed process payloads stay excluded. */
internal data class TailFollowSignature(
    val groupKey: Long,
    val chunkCount: Int = 0,
    val visibleTextLength: Int = 0,
)

internal fun tailFollowSignature(group: TimelineRenderGroup?): TailFollowSignature? =
    when (group) {
        is TimelineRenderGroup.AssistantRun -> {
            val chunks = chunkAssistantRun(group.items)
            TailFollowSignature(
                groupKey = group.key,
                chunkCount = chunks.size,
                visibleTextLength =
                    chunks.sumOf { chunk ->
                        when (chunk) {
                            is AssistantRenderChunk.Text -> chunk.block.text.length
                            is AssistantRenderChunk.Process -> 0
                        }
                    },
            )
        }
        is TimelineRenderGroup.Single -> TailFollowSignature(group.key)
        null -> null
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
            shadowElevation = 0.dp,
            border = BorderStroke(if (darkScheme) 1.dp else 0.5.dp, MaterialTheme.colorScheme.outline),
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

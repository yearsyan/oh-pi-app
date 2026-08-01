package io.github.yearsyan.pi.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.TimelineItem
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.ui.components.AssistantMessageRow
import io.github.yearsyan.pi.ui.components.ChatTopBar
import io.github.yearsyan.pi.ui.components.Composer
import io.github.yearsyan.pi.ui.components.ConfirmDialog
import io.github.yearsyan.pi.ui.components.ExtensionDialog
import io.github.yearsyan.pi.ui.components.RenameDialog
import io.github.yearsyan.pi.ui.components.StatusLine
import io.github.yearsyan.pi.ui.components.ToolCallCard
import io.github.yearsyan.pi.ui.components.UserMessageRow

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
) {
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopBar(
            controller = controller,
            showBack = showBack,
            onBack = onBack,
            onRename = { renameOpen = true },
            onDelete = { deleteOpen = true },
        )

        val bodyState = when {
            controller.items.isNotEmpty() -> ChatBodyState.Messages
            controller.conn == ConnState.Connecting || controller.isLoadingHistory -> ChatBodyState.Loading
            else -> ChatBodyState.Empty
        }
        Crossfade(
            targetState = bodyState,
            modifier = Modifier.weight(1f),
            animationSpec = tween(durationMillis = 180),
            label = "chatBody",
        ) { state ->
            when (state) {
                ChatBodyState.Loading -> SessionLoadingState(Modifier.fillMaxSize())
                ChatBodyState.Empty -> EmptyChatState(Modifier.fillMaxSize())
                ChatBodyState.Messages -> MessageList(controller, Modifier.fillMaxSize())
            }
        }

        Composer(
            conn = controller.conn,
            isStreaming = controller.isStreaming,
            onSend = { controller.sendPrompt(it) },
            onStop = { controller.abort() },
        )
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
private fun MessageList(controller: ChatController, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var pinned by remember { mutableStateOf(true) }

    // track whether the user is near the bottom
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow true
            val total = info.totalItemsCount
            total == 0 || last.index >= total - 2
        }.collect { pinned = it }
    }

    // auto-scroll when pinned and new content arrives
    val itemCount by remember { derivedStateOf { controller.items.size } }
    LaunchedEffect(itemCount) {
        if (pinned && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
    ) {
        items(controller.items, key = { it.key }) { item ->
            when (item) {
                is TimelineItem.UserItem -> UserMessageRow(item)
                is TimelineItem.AssistantItem -> AssistantMessageRow(item)
                is TimelineItem.ToolItem -> Box(Modifier.padding(horizontal = 56.dp)) {
                    ToolCallCard(item.tool)
                }
                is TimelineItem.StatusItem -> StatusLine(item)
            }
        }
    }

    // keep the tail visible while streaming text grows
    val lastItem = controller.items.lastOrNull()
    LaunchedEffect(lastItem?.let { (it as? TimelineItem.AssistantItem)?.blocks?.size }) {
        if (pinned && controller.items.isNotEmpty()) {
            listState.scrollToItem(controller.items.size - 1)
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

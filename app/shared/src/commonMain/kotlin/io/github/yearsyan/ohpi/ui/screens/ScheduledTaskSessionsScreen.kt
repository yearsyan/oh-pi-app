package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.Toast
import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.GatewayScheduledTaskSession
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.components.relativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ScheduledTaskSessionsScreen(
    vm: AppViewModel,
    taskId: String,
    taskName: String,
    onBack: () -> Unit,
    onOpenSession: (SavedSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = S
    var sessions by remember(vm.activeServerId, taskId) {
        mutableStateOf<List<GatewayScheduledTaskSession>>(emptyList())
    }
    var sessionCount by remember(vm.activeServerId, taskId) { mutableStateOf(0) }
    var nextCursor by remember(vm.activeServerId, taskId) { mutableStateOf("") }
    var loading by remember(vm.activeServerId, taskId) { mutableStateOf(true) }
    var refreshing by remember(vm.activeServerId, taskId) { mutableStateOf(false) }
    var loadingMore by remember(vm.activeServerId, taskId) { mutableStateOf(false) }
    var error by remember(vm.activeServerId, taskId) { mutableStateOf<String?>(null) }

    suspend fun load(reset: Boolean) {
        if (reset) {
            refreshing = !loading
            error = null
        } else {
            if (nextCursor.isBlank() || loadingMore) return
            loadingMore = true
        }
        try {
            val page = vm.loadScheduledTaskSessions(taskId, if (reset) "" else nextCursor)
            sessions = if (reset) {
                page.sessions
            } else {
                (sessions + page.sessions).distinctBy { it.session.id }
            }
            sessionCount = page.sessionCount
            nextCursor = page.nextCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val message = strings.scheduledTaskSessionsLoadFailed(failure.message ?: strings.unknownError)
            error = message
            if (!reset) vm.toast(message, Toast.Kind.Error)
        } finally {
            loading = false
            refreshing = false
            loadingMore = false
        }
    }

    LaunchedEffect(vm.activeServerId, taskId) { load(reset = true) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    S.scheduledTaskSessionsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (taskName.isNotBlank()) {
                    Text(
                        taskName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                enabled = !refreshing && !loading,
                onClick = { scope.launch { load(reset = true) } },
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = S.retry)
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            error != null && sessions.isEmpty() -> TaskSessionsMessage(
                title = S.scheduledTaskSessionsTitle,
                body = error.orEmpty(),
                action = S.retry,
                onAction = { scope.launch { loading = true; load(reset = true) } },
            )

            sessions.isEmpty() -> TaskSessionsMessage(
                title = S.scheduledTaskSessionsEmpty,
                body = S.scheduledTaskSessionsEmptyHint,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                itemsIndexed(sessions, key = { _, item -> item.session.id }) { index, item ->
                    ScheduledTaskSessionRow(item = item, onClick = { onOpenSession(item.session) })
                    if (index < sessions.lastIndex || nextCursor.isNotBlank()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                if (nextCursor.isNotBlank()) {
                    item(key = "load-more") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !loadingMore) {
                                    scope.launch { load(reset = false) }
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                S.showMoreSessions((sessionCount - sessions.size).coerceAtLeast(1)),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledTaskSessionRow(
    item: GatewayScheduledTaskSession,
    onClick: () -> Unit,
) {
    val session = item.session
    val workspaceLabel = item.workspaceName.ifBlank {
        session.workspaceDirectory
            .trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { session.workspaceId.take(8) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.workspaceDeleted, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (item.workspaceDeleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.name.ifBlank { S.untitledSession },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (item.workspaceDeleted) {
                    "$workspaceLabel · ${S.scheduledTaskSessionWorkspaceDeleted}"
                } else {
                    "$workspaceLabel · ${relativeTime(session.lastActive)}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (item.workspaceDeleted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                session.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (session.running) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun TaskSessionsMessage(
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(12.dp),
            )
        }
    }
}

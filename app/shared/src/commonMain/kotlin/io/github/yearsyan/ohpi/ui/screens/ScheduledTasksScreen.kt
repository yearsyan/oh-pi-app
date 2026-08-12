package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.GatewayScheduledTask
import io.github.yearsyan.ohpi.net.GatewayScheduledTaskMutation
import io.github.yearsyan.ohpi.net.ScheduledTaskKinds
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.components.ConfirmDialog
import io.github.yearsyan.ohpi.ui.components.SheetAction
import io.github.yearsyan.ohpi.ui.components.longPressHaptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScheduledTasksScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onSessions: (GatewayScheduledTask) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = S
    var tasks by remember(vm.activeServerId) { mutableStateOf<List<GatewayScheduledTask>>(emptyList()) }
    var loading by remember(vm.activeServerId) { mutableStateOf(true) }
    var refreshing by remember(vm.activeServerId) { mutableStateOf(false) }
    var mutatingTaskId by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<GatewayScheduledTask?>(null) }

    suspend fun load(showSpinner: Boolean) {
        if (showSpinner) refreshing = true
        try {
            tasks = vm.loadScheduledTasks()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            vm.toast(strings.scheduledTaskLoadFailed(failure.message ?: strings.unknownError), Toast.Kind.Error)
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(vm.activeServerId, vm.activeGatewayInfo?.supportsScheduledTasks) {
        if (vm.activeGatewayInfo?.supportsScheduledTasks == true) load(showSpinner = false)
        else loading = false
    }

    fun mutate(task: GatewayScheduledTask, action: suspend () -> GatewayScheduledTask) {
        if (mutatingTaskId != null) return
        mutatingTaskId = task.id
        scope.launch {
            try {
                val updated = action()
                tasks = tasks.map { if (it.id == updated.id) updated else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                vm.toast(strings.scheduledTaskSaveFailed(failure.message ?: strings.unknownError), Toast.Kind.Error)
            } finally {
                if (mutatingTaskId == task.id) mutatingTaskId = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
            }
            Text(
                S.scheduledTasksTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !refreshing && vm.activeGatewayInfo?.supportsScheduledTasks == true,
                onClick = { scope.launch { load(showSpinner = true) } },
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = S.retry)
                }
            }
            IconButton(
                enabled = vm.activeGatewayInfo?.supportsScheduledTasks == true,
                onClick = onAdd,
            ) {
                Icon(Icons.Filled.Add, contentDescription = S.scheduledTaskAdd)
            }
        }

        when {
            vm.activeGatewayInfo?.supportsScheduledTasks != true -> ScheduledTasksMessage(
                icon = { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(36.dp)) },
                title = S.scheduledTasksTitle,
                body = S.scheduledTasksUnsupported,
            )

            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            tasks.isEmpty() -> ScheduledTasksMessage(
                icon = { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(36.dp)) },
                title = S.scheduledTasksEmpty,
                body = S.scheduledTasksEmptyHint,
                action = {
                    Text(
                        S.scheduledTaskAdd,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                    ScheduledTaskRow(
                        task = task,
                        workspace = vm.workspaces.firstOrNull { it.id == task.workspaceId },
                        busy = mutatingTaskId == task.id,
                        onEdit = { onEdit(task.id) },
                        onSessions = { onSessions(task) },
                        sessionsSupported = vm.activeGatewayInfo?.supportsScheduledTaskSessions == true,
                        onToggle = { enabled ->
                            mutate(task) {
                                vm.updateScheduledTask(task.id, task.toMutation(enabled = enabled))
                            }
                        },
                        onRun = {
                            if (mutatingTaskId == null) {
                                mutatingTaskId = task.id
                                scope.launch {
                                    try {
                                        var updated = vm.runScheduledTaskNow(task.id)
                                        tasks = tasks.map { if (it.id == task.id) updated else it }
                                        vm.toast(strings.scheduledTaskRunStarted, Toast.Kind.Success)
                                        for (attempt in 0 until 60) {
                                            if (updated.currentRun == null) break
                                            delay(2_000)
                                            updated = vm.loadScheduledTask(task.id)
                                            tasks = tasks.map { if (it.id == task.id) updated else it }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Throwable) {
                                        vm.toast(
                                            strings.scheduledTaskRunFailed(failure.message ?: strings.unknownError),
                                            Toast.Kind.Error,
                                        )
                                    } finally {
                                        if (mutatingTaskId == task.id) mutatingTaskId = null
                                    }
                                }
                            }
                        },
                        onDelete = { deleteCandidate = task },
                    )
                    if (index < tasks.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }

    deleteCandidate?.let { task ->
        ConfirmDialog(
            title = S.scheduledTaskDeleteTitle,
            body = S.scheduledTaskDeleteBody(task.name),
            confirmLabel = S.delete,
            onDismiss = { deleteCandidate = null },
            onConfirm = {
                deleteCandidate = null
                mutatingTaskId = task.id
                scope.launch {
                    try {
                        vm.deleteScheduledTask(task.id)
                        tasks = tasks.filterNot { it.id == task.id }
                        vm.toast(strings.scheduledTaskDeleted, Toast.Kind.Success)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        vm.toast(
                            strings.scheduledTaskDeleteFailed(failure.message ?: strings.unknownError),
                            Toast.Kind.Error,
                        )
                    } finally {
                        if (mutatingTaskId == task.id) mutatingTaskId = null
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTaskRow(
    task: GatewayScheduledTask,
    workspace: WorkspaceSummary?,
    busy: Boolean,
    onEdit: () -> Unit,
    onSessions: () -> Unit,
    sessionsSupported: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = S
    var actionSheetOpen by remember { mutableStateOf(false) }
    val running = task.currentRun != null
    val next = task.nextRunAt
    val last = task.lastRun
    val workspaceName = workspace?.displayName ?: task.workspaceId
    val statusText = when {
        running -> strings.scheduledTaskRunning
        next != null -> "${strings.scheduledTaskNextRun}: ${formatScheduledTaskTime(next)}"
        last != null ->
            "${strings.scheduledTaskLastRun}: ${last.status} · ${formatScheduledTaskTime(last.startedAt)}"
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !busy,
                onClick = onEdit,
                onLongClick = {
                    longPressHaptic()
                    actionSheetOpen = true
                },
            )
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                task.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                scheduledTaskScheduleSummary(task, strings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (statusText != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "$workspaceName · $statusText",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        running -> MaterialTheme.colorScheme.primary
                        last?.status == "failed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Switch(
            checked = task.enabled,
            enabled = !busy && !running,
            onCheckedChange = onToggle,
        )
    }

    if (actionSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { actionSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                SheetAction(
                    icon = { tint -> Icon(Icons.Filled.PlayArrow, null, Modifier.size(19.dp), tint = tint) },
                    label = strings.scheduledTaskRunNow,
                    enabled = !running,
                ) {
                    actionSheetOpen = false
                    onRun()
                }
                SheetAction(
                    icon = { tint ->
                        Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(19.dp), tint = tint)
                    },
                    label = strings.scheduledTaskSessionsAction,
                    enabled = sessionsSupported,
                ) {
                    actionSheetOpen = false
                    onSessions()
                }
                SheetAction(
                    icon = { tint -> Icon(Icons.Filled.Edit, null, Modifier.size(19.dp), tint = tint) },
                    label = strings.edit,
                ) {
                    actionSheetOpen = false
                    onEdit()
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SheetAction(
                    icon = { tint -> Icon(Icons.Filled.Delete, null, Modifier.size(19.dp), tint = tint) },
                    label = strings.delete,
                    destructive = true,
                    enabled = !running,
                ) {
                    actionSheetOpen = false
                    onDelete()
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actionSheetOpen = false }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(S.cancel, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun ScheduledTasksMessage(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

internal fun GatewayScheduledTask.toMutation(enabled: Boolean = this.enabled): GatewayScheduledTaskMutation =
    GatewayScheduledTaskMutation(
        name = name,
        workspaceId = workspaceId,
        model = model,
        thinking = thinking,
        skillPaths = skillPaths,
        noSkills = noSkills,
        prompt = prompt,
        schedule = schedule,
        enabled = enabled,
    )

internal fun scheduledTaskScheduleSummary(task: GatewayScheduledTask, strings: Strings): String =
    when (task.schedule.kind) {
        ScheduledTaskKinds.Cron -> {
            val weekly = parseScheduledTaskWeeklyCron(task.schedule.expression)
            if (weekly == null) {
                "${strings.scheduledTaskCron} · ${task.schedule.timezone}"
            } else {
                val weekdaySummary =
                    if (weekly.weekdays.size == 7) {
                        strings.scheduledTaskEveryDay
                    } else {
                        weekly.weekdays
                            .sorted()
                            .joinToString(", ") { strings.scheduledTaskWeekdayLabels[it - 1] }
                    }
                val time =
                    weekly.hour.toString().padStart(2, '0') + ":" +
                        weekly.minute.toString().padStart(2, '0')
                "$weekdaySummary · $time · ${task.schedule.timezone}"
            }
        }
        ScheduledTaskKinds.Interval -> "${task.schedule.everySeconds}s · ${task.schedule.anchorAt.orEmpty()}"
        ScheduledTaskKinds.Once -> task.schedule.at.orEmpty()
        else -> task.schedule.kind
    }

internal fun formatScheduledTaskTime(raw: String): String =
    raw.replace('T', ' ').removeSuffix("Z").let { value ->
        if (value.length > 25) value.take(25) else value
    }

package io.github.yearsyan.pi.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.SessionStats
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.theme.piExtras
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Chat top bar: back (compact), title, status and overflow actions. */
@Composable
fun ChatTopBar(
    controller: ChatController,
    showBack: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBrowseFiles: () -> Unit = {},
) {
    var sessionInfoOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                val draft = controller.isDraft
                val name = controller.sessionName
                val namePending = name.isBlank() && !draft &&
                    (controller.conn == ConnState.Connecting || controller.isLoadingHistory)
                if (namePending) {
                    Box(
                        Modifier
                            .padding(vertical = 3.dp)
                            .width(132.dp)
                            .height(15.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                } else {
                    Text(
                        name.ifBlank { if (draft) S.newChat else S.untitledSession },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(2.dp))
                if (!draft || controller.conn != ConnState.Disconnected) {
                    ConnectionBadge(controller)
                }
            }
            SessionUsageButton(controller)
            OverflowMenu(
                onRename = onRename,
                onDelete = onDelete,
                onReconnect = { controller.reconnect() },
                canReconnect = controller.canReconnect,
                onBrowseFiles = onBrowseFiles,
                onSessionInfo = { sessionInfoOpen = true },
                sessionInfoEnabled = controller.sessionId.isNotBlank(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    if (sessionInfoOpen) {
        SessionInfoDialog(
            sessionId = controller.sessionId,
            workDir = controller.workDir,
            onDismiss = { sessionInfoOpen = false },
        )
    }
}

@Composable
private fun SessionUsageButton(controller: ChatController) {
    var dialogOpen by remember(controller) { mutableStateOf(false) }
    val stats = controller.sessionStats
    val percent = stats?.contextUsage?.percent
    val canInspect = stats != null || controller.conn == ConnState.Ready
    val description = buildString {
        append(S.contextUsage).append(": ")
        append(percent?.let(::formatPercent) ?: S.usageUnavailable)
    }

    IconButton(
        onClick = {
            dialogOpen = true
            controller.refreshSessionStats()
        },
        enabled = canInspect,
    ) {
        Box(
            modifier = Modifier.size(32.dp).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (controller.sessionStatsLoading && stats == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(27.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                ContextUsageRing(percent = percent, ringSize = 30.dp, compact = true)
            }
        }
    }

    if (dialogOpen) {
        SessionUsageDialog(
            stats = stats,
            refreshing = controller.sessionStatsLoading,
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun SessionUsageDialog(
    stats: SessionStats?,
    refreshing: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(S.sessionUsageTitle)
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (stats == null) {
                    Text(
                        S.usageUnavailable,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ContextUsageSection(stats)
                    HorizontalDivider()
                    UsageSectionTitle(S.cacheUsage)
                    StatRow(S.cacheHitRate, stats.cacheHitPercent?.let(::formatPercent) ?: "—")
                    StatRow(S.cacheRead, formatCount(stats.tokens.cacheRead))
                    StatRow(S.cacheWrite, formatCount(stats.tokens.cacheWrite))
                    HorizontalDivider()
                    UsageSectionTitle(S.tokenUsage)
                    StatRow(S.inputTokens, formatCount(stats.tokens.input))
                    StatRow(S.outputTokens, formatCount(stats.tokens.output))
                    StatRow(S.totalTokens, formatCount(stats.tokens.total))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(S.dialogOk) }
        },
    )
}

@Composable
private fun ContextUsageSection(stats: SessionStats) {
    val context = stats.contextUsage
    val percent = context?.percent
    UsageSectionTitle(S.contextUsage)
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextUsageRing(percent = percent, ringSize = 76.dp, compact = false)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatRow(
                S.contextWindow,
                context?.contextWindow?.takeIf { it > 0L }?.let(::formatCount) ?: "—",
            )
            StatRow(S.contextUsed, context?.tokens?.let(::formatCount) ?: "—")
            val remaining = context?.tokens?.let { used ->
                (context.contextWindow - used).coerceAtLeast(0L)
            }
            StatRow(S.contextRemaining, remaining?.let(::formatCount) ?: "—")
        }
    }
    if (context == null || context.tokens == null) {
        Text(
            S.contextUnknown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ContextUsageRing(
    percent: Double?,
    ringSize: Dp,
    compact: Boolean,
) {
    val ringColor = contextUsageColor(percent)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    val progress = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val strokeWidth = if (compact) 3.dp else 7.dp
    Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = size.minDimension - stroke
            val origin = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(diameter, diameter)
            drawCircle(
                color = trackColor,
                radius = diameter / 2f,
                style = Stroke(width = stroke),
            )
            if (percent != null && progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = origin,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = when {
                percent == null -> "?"
                compact -> percent.roundToInt().coerceIn(0, 999).toString()
                else -> formatPercent(percent)
            },
            style = if (compact) {
                MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            } else {
                MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = ringColor,
        )
    }
}

@Composable
private fun contextUsageColor(percent: Double?): Color = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent > 90.0 -> MaterialTheme.colorScheme.error
    percent > 70.0 -> piExtras.warning
    else -> MaterialTheme.colorScheme.primary
}

private fun formatCount(value: Long): String =
    value.coerceAtLeast(0L).toString().reversed().chunked(3).joinToString(",").reversed()

private fun formatPercent(value: Double): String {
    val tenths = (value.coerceAtLeast(0.0) * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}

@Composable
private fun ConnectionBadge(controller: ChatController) {
    val status = resolveSessionStatus(controller.conn, controller.syncPhase)
    val color = when (status) {
        SessionStatus.Connected -> piExtras.success
        SessionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        SessionStatus.ConnectionError -> MaterialTheme.colorScheme.error
        SessionStatus.Connecting,
        SessionStatus.RestoringSession,
        SessionStatus.SyncingLatestActivity -> piExtras.warning
    }
    val label = status.localizedLabel(controller.syncProgress)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        if (controller.isStreaming) {
            Spacer(Modifier.width(6.dp))
            WorkingIndicator()
            Spacer(Modifier.width(5.dp))
            Text(
                if (controller.runningToolCount > 0) S.agentWorkingTool
                else "${controller.charRate.toInt()} char/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Pulsing dot shown while the agent is working; hidden otherwise. */
@Composable
private fun WorkingIndicator() {
    val alpha by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "workingAlpha",
    )
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)),
    )
}

@Composable
private fun OverflowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReconnect: () -> Unit,
    canReconnect: Boolean,
    onBrowseFiles: () -> Unit,
    onSessionInfo: () -> Unit,
    sessionInfoEnabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(S.browseFiles) },
                leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                onClick = { onBrowseFiles(); open = false },
            )
            DropdownMenuItem(
                text = { Text(S.reconnect) },
                leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                onClick = { onReconnect(); open = false },
                enabled = canReconnect,
            )
            DropdownMenuItem(
                text = { Text(S.rename) },
                leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
                onClick = { onRename(); open = false },
            )
            DropdownMenuItem(
                text = { Text(S.sessionInfo) },
                leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(18.dp)) },
                onClick = { onSessionInfo(); open = false },
                enabled = sessionInfoEnabled,
            )
            DropdownMenuItem(
                text = { Text(S.delete) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                onClick = { onDelete(); open = false },
            )
        }
    }
}

@Composable
private fun SessionInfoDialog(
    sessionId: String,
    workDir: String,
    onDismiss: () -> Unit,
) {
    val sessionDir = "~/.local/state/pi2ws/sessions/$sessionId"
    val resumeCommand =
        "pi --session-dir ~/.local/state/pi2ws/sessions/$sessionId --session-id $sessionId"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.sessionInfo) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                InfoField(S.sessionId, sessionId)
                InfoField(S.workDir, workDir)
                InfoField("pi2ws session dir", sessionDir)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        S.resumeCommand,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        S.resumeCommandHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CopyableCode(resumeCommand)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(S.dialogOk) }
        },
    )
}

@Composable
private fun InfoField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // weight must sit on SelectionContainer itself: it is the Row's
            // direct child, so a weight on the inner Text is silently ignored
            // and long values would push CopyButton out of the row.
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            CopyButton(value)
        }
    }
}

@Composable
private fun CopyableCode(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        CopyButton(text)
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(copied) {
            delay(1500)
            copied = false
        }
    }
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
    ) {
        Text(
            if (copied) S.copied else S.copy,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

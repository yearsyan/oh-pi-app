package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.chat.ModelInfo
import io.github.yearsyan.ohpi.data.ConnState
import io.github.yearsyan.ohpi.i18n.S

@Composable
private fun SelectorChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .widthIn(max = 156.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
internal fun ModelSelector(controller: ChatController) {
    var open by remember { mutableStateOf(false) }
    val retry = controller.canConfigureDraft && controller.capabilitiesError != null && controller.models.isEmpty()
    val enabled =
        (controller.conn == ConnState.Ready && controller.models.isNotEmpty()) ||
            (controller.canConfigureDraft && (controller.models.isNotEmpty() || retry))
    val label = when {
        controller.capabilitiesLoading -> S.loadingModels
        retry -> S.retryModels
        else -> controller.currentModel?.label ?: controller.model.ifBlank { S.noModel }
    }
    Box {
        SelectorChip(
            label = label,
            enabled = enabled,
            onClick = {
                if (retry) controller.reloadCapabilities() else open = true
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                S.selectModel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            controller.models.forEach { model: ModelInfo ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.label, style = MaterialTheme.typography.bodyMedium)
                                if (model.provider.isNotBlank()) {
                                    Text(
                                        model.provider,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (
                                model.id == controller.currentModel?.id &&
                                model.provider == controller.currentModel?.provider
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.setModel(model)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun ThinkingSelector(controller: ChatController) {
    var open by remember { mutableStateOf(false) }
    val enabled =
        controller.thinkingLevels.isNotEmpty() &&
            (controller.conn == ConnState.Ready || controller.canConfigureDraft)
    Box {
        SelectorChip(
            label = controller.thinkingLevel.ifBlank { S.thinkingLevel },
            enabled = enabled,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                S.selectThinkingLevel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            controller.thinkingLevels.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(level, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (level == controller.thinkingLevel) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.selectThinkingLevel(level)
                        open = false
                    },
                )
            }
        }
    }
}

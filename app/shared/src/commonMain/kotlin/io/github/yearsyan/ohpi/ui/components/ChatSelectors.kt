package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.ChatController
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
    val retry = controller.canConfigureDraft && controller.capabilitiesError != null && controller.models.isEmpty()
    val enabled =
        (controller.conn == ConnState.Ready && controller.models.isNotEmpty()) ||
            (controller.canConfigureDraft && (controller.models.isNotEmpty() || retry))
    val label = when {
        controller.capabilitiesLoading -> S.loadingModels
        retry -> S.retryModels
        else -> controller.currentModel?.label ?: controller.model.ifBlank { S.noModel }
    }
    val items =
        controller.models.mapIndexed { index, model ->
            AppMenuItem(
                id = index.toString(),
                title = model.label,
                subtitle = model.provider.takeIf { it.isNotBlank() },
                checkable = true,
                selected =
                    model.id == controller.currentModel?.id &&
                        model.provider == controller.currentModel?.provider,
            )
        }
    AppDropdownMenu(
        items = items,
        onItemClick = { id ->
            id.toIntOrNull()?.let { index -> controller.models.getOrNull(index) }?.let(controller::setModel)
        },
        title = S.selectModel,
        enabled = enabled && !retry,
        accessibilityLabel = S.selectModel,
    ) { openMenu ->
        SelectorChip(
            label = label,
            enabled = enabled,
            onClick = {
                if (retry) controller.reloadCapabilities() else openMenu()
            },
        )
    }
}

@Composable
internal fun ThinkingSelector(controller: ChatController) {
    val enabled =
        controller.thinkingLevels.isNotEmpty() &&
            (controller.conn == ConnState.Ready || controller.canConfigureDraft)
    AppDropdownMenu(
        items =
            controller.thinkingLevels.map { level ->
                AppMenuItem(
                    id = level,
                    title = level,
                    checkable = true,
                    selected = level == controller.thinkingLevel,
                )
            },
        onItemClick = controller::selectThinkingLevel,
        title = S.selectThinkingLevel,
        enabled = enabled,
        accessibilityLabel = S.selectThinkingLevel,
    ) { openMenu ->
        SelectorChip(
            label = controller.thinkingLevel.ifBlank { S.thinkingLevel },
            enabled = enabled,
            onClick = openMenu,
        )
    }
}

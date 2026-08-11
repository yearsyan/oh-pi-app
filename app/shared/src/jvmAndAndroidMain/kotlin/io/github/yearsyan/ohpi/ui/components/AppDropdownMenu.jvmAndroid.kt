package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal actual fun AppDropdownMenu(
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
    modifier: Modifier,
    title: String?,
    enabled: Boolean,
    accessibilityLabel: String?,
    anchor: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, items.isEmpty()) {
        if (!enabled || items.isEmpty()) expanded = false
    }

    Box(modifier) {
        anchor {
            if (enabled && items.isNotEmpty()) expanded = true
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            items.forEachIndexed { index, item ->
                if (index > 0 && item.startsSection) HorizontalDivider()
                DropdownMenuItem(
                    text = { MaterialMenuItemText(item) },
                    leadingIcon =
                        item.icon?.let { icon ->
                            {
                                Icon(
                                    icon.imageVector,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    onClick = {
                        expanded = false
                        onItemClick(item.id)
                    },
                    enabled = item.enabled,
                )
            }
        }
    }
}

@Composable
private fun MaterialMenuItemText(item: AppMenuItem) {
    if (item.subtitle != null || item.checkable) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = item.titleMaxLines,
                    overflow =
                        if (item.titleMaxLines == Int.MAX_VALUE) {
                            TextOverflow.Clip
                        } else {
                            TextOverflow.Ellipsis
                        },
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = item.subtitleMaxLines,
                        overflow =
                            if (item.subtitleMaxLines == Int.MAX_VALUE) {
                                TextOverflow.Clip
                            } else {
                                TextOverflow.Ellipsis
                            },
                    )
                }
            }
            if (item.checkable && item.selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    } else {
        Text(item.title)
    }
}

private val AppMenuIcon.imageVector: ImageVector
    get() =
        when (this) {
            AppMenuIcon.Folder -> Icons.Filled.Folder
            AppMenuIcon.Refresh -> Icons.Filled.Refresh
            AppMenuIcon.Edit -> Icons.Filled.Edit
            AppMenuIcon.Archive -> Icons.Filled.Archive
            AppMenuIcon.Info -> Icons.Filled.Info
            AppMenuIcon.Stop -> Icons.Filled.PowerSettingsNew
            AppMenuIcon.Delete -> Icons.Filled.Delete
        }

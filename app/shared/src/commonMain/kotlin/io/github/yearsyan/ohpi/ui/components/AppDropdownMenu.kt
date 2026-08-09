package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/** Icons supported by the cross-platform app menu renderer. */
internal enum class AppMenuIcon {
    Folder,
    Refresh,
    Edit,
    Info,
    Stop,
    Delete,
}

/**
 * Platform-neutral description of one app menu action.
 *
 * Android and desktop render this with Material 3. iOS maps it to a native
 * [platform.UIKit.UIAction], including subtitles, checkmarks and system icons.
 */
@Immutable
internal data class AppMenuItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val titleMaxLines: Int = Int.MAX_VALUE,
    val subtitleMaxLines: Int = Int.MAX_VALUE,
    val icon: AppMenuIcon? = null,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val startsSection: Boolean = false,
)

/**
 * App-owned dropdown menu boundary.
 *
 * [anchor] receives the callback that opens the menu on Android and desktop.
 * On iOS a native menu button is placed over the same anchor, so the visible
 * trigger stays Compose while UIKit owns menu presentation and interaction.
 */
@Composable
internal expect fun AppDropdownMenu(
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    enabled: Boolean = true,
    accessibilityLabel: String? = title,
    anchor: @Composable (openMenu: () -> Unit) -> Unit,
)

/**
 * Wraps [content] in a platform context-menu trigger.
 *
 * Desktop opens the menu at the pointer on a secondary click. Touch platforms
 * render [content] unchanged and keep their existing menu affordances.
 */
@Composable
internal expect fun AppContextMenu(
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
)

/** Splits items into visual sections without producing empty leading groups. */
internal fun List<AppMenuItem>.menuSections(): List<List<AppMenuItem>> {
    if (isEmpty()) return emptyList()

    val sections = mutableListOf<MutableList<AppMenuItem>>()
    forEach { item ->
        if (sections.isEmpty() || (item.startsSection && sections.last().isNotEmpty())) {
            sections += mutableListOf<AppMenuItem>()
        }
        sections.last() += item
    }
    return sections
}

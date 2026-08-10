package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeCustom
import platform.UIKit.UIContextMenuConfigurationElementOrderFixed
import platform.UIKit.UIImage
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElement
import platform.UIKit.UIMenuElementAttributesDestructive
import platform.UIKit.UIMenuElementAttributesDisabled
import platform.UIKit.UIMenuElementState
import platform.UIKit.UIMenuOptionsDisplayInline
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.accessibilityLabel
import platform.UIKit.isAccessibilityElement

/**
 * UIKit owns the iOS menu surface. Standard UIMenu presentation automatically
 * adopts Liquid Glass on iOS 26 and keeps the native appearance on iOS 18.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
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
    val latestOnItemClick by rememberUpdatedState(onItemClick)
    val nativeMenu =
        remember(title, items) {
            buildNativeMenu(title, items) { itemId -> latestOnItemClick(itemId) }
        }
    val menuEnabled = enabled && items.isNotEmpty()
    val darkAppearance = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // UIMenu reads UIKit traits rather than Compose's MaterialTheme. Keep the
    // native source view in sync so the system menu retains Liquid Glass while
    // matching an app-forced light or dark appearance.
    val interfaceStyle =
        if (darkAppearance) {
            UIUserInterfaceStyle.UIUserInterfaceStyleDark
        } else {
            UIUserInterfaceStyle.UIUserInterfaceStyleLight
        }

    Box(modifier) {
        // The transparent native UIButton below receives the tap when enabled.
        anchor {}
        if (menuEnabled) {
            UIKitView(
                factory = {
                    UIButton.buttonWithType(UIButtonTypeCustom).apply {
                        showsMenuAsPrimaryAction = true
                        preferredMenuElementOrder = UIContextMenuConfigurationElementOrderFixed
                        isAccessibilityElement = true
                        opaque = false
                        overrideUserInterfaceStyle = interfaceStyle
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = { button ->
                    button.menu = nativeMenu
                    button.enabled = menuEnabled
                    button.accessibilityLabel = accessibilityLabel ?: title
                    button.overrideUserInterfaceStyle = interfaceStyle
                },
                properties =
                    UIKitInteropProperties(
                        interactionMode = UIKitInteropInteractionMode.NonCooperative,
                        isNativeAccessibilityEnabled = true,
                        placedAsOverlay = true,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun buildNativeMenu(
    title: String?,
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
): UIMenu {
    val sections = items.menuSections()
    val children: List<UIMenuElement> =
        if (sections.size <= 1) {
            sections.firstOrNull().orEmpty().map { it.toNativeAction(onItemClick) }
        } else {
            sections.map { section ->
                UIMenu.menuWithTitle(
                    title = "",
                    image = null,
                    identifier = null,
                    options = UIMenuOptionsDisplayInline,
                    children = section.map { it.toNativeAction(onItemClick) },
                )
            }
        }
    return UIMenu.menuWithTitle(title.orEmpty(), children)
}

@OptIn(ExperimentalForeignApi::class)
private fun AppMenuItem.toNativeAction(onItemClick: (String) -> Unit): UIAction {
    val action =
        UIAction.actionWithTitle(
            title = title,
            image = icon?.let { UIImage.systemImageNamed(it.systemImageName) },
            identifier = null,
        ) {
            onItemClick(id)
        }
    action.subtitle = subtitle?.takeIf { it.isNotBlank() }
    action.attributes =
        (if (enabled) 0uL else UIMenuElementAttributesDisabled) or
            (if (destructive) UIMenuElementAttributesDestructive else 0uL)
    action.state =
        if (checkable && selected) {
            UIMenuElementState.UIMenuElementStateOn
        } else {
            UIMenuElementState.UIMenuElementStateOff
        }
    return action
}

private val AppMenuIcon.systemImageName: String
    get() =
        when (this) {
            AppMenuIcon.Folder -> "folder"
            AppMenuIcon.Refresh -> "arrow.clockwise"
            AppMenuIcon.Edit -> "pencil"
            AppMenuIcon.Info -> "info.circle"
            AppMenuIcon.Stop -> "stop.circle"
            AppMenuIcon.Delete -> "trash"
        }

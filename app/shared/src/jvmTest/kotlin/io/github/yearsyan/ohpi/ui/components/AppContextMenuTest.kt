package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppContextMenuTest {
    @Test
    fun secondaryClickOpensMenuAndInvokesSelectedAction() = runComposeUiTest {
        var selectedItem: String? = null
        setContent {
            PiTheme {
                AppContextMenu(
                    items = listOf(AppMenuItem(id = "rename", title = "Rename")),
                    onItemClick = { selectedItem = it },
                ) {
                    Box(Modifier.size(120.dp).testTag("context-menu-anchor"))
                }
            }
        }

        onNodeWithTag("context-menu-anchor").performMouseInput { rightClick() }
        onNodeWithText("Rename").performClick()

        runOnIdle { assertEquals("rename", selectedItem) }
    }
}

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.yearsyan.ohpi.chat.TimelineImage
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.test.Test

private const val OnePixelPng =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

@OptIn(ExperimentalTestApi::class)
class UserMessageRowTest {
    @Test
    fun imageIsRenderedOutsideTheTextBubbleWithoutAttachmentLabel() = runComposeUiTest {
        setContent {
            PiTheme {
                UserMessageRow(
                    TimelineItem.UserItem(
                        key = 1L,
                        text = "inspect this",
                        ts = 1L,
                        images = listOf(TimelineImage(OnePixelPng, "image/png")),
                    ),
                )
            }
        }

        onNodeWithTag(UserImageThumbnailTag).assertExists()
        onNodeWithText("inspect this").assertExists()
        onNodeWithText("Image").assertDoesNotExist()
    }

    @Test
    fun tappingThumbnailOpensImagePreview() = runComposeUiTest {
        setContent {
            // The test root cannot see into the separate preview window's
            // composition, so the preview falls back to the in-app dialog.
            CompositionLocalProvider(LocalImagePreviewWindowed provides false) {
                PiTheme {
                    UserMessageRow(
                        TimelineItem.UserItem(
                            key = 1L,
                            text = "",
                            ts = 1L,
                            images = listOf(TimelineImage(OnePixelPng, "image/png")),
                        ),
                    )
                }
            }
        }

        onNodeWithTag(UserImageThumbnailTag).performClick()
        onNodeWithTag(UserImagePreviewTag).assertExists()
        onNodeWithText("Image").assertDoesNotExist()
    }
}

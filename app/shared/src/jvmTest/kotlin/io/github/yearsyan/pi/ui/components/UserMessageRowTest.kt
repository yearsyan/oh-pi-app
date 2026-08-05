package io.github.yearsyan.pi.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.yearsyan.pi.chat.TimelineImage
import io.github.yearsyan.pi.chat.TimelineItem
import io.github.yearsyan.pi.theme.PiTheme
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

        onNodeWithTag(UserImageThumbnailTag).performClick()
        onNodeWithTag(UserImagePreviewTag).assertExists()
        onNodeWithText("Image").assertDoesNotExist()
    }
}

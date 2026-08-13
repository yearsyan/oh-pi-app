package io.github.yearsyan.ohpi.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.yearsyan.ohpi.chat.AssistantBlock
import io.github.yearsyan.ohpi.chat.BlockKind
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ThinkingDetailTest {
    @Test
    fun streamingTextUpdatesAtBoundedCadence() = runComposeUiTest {
        val initial = "initial streaming thought"
        val updated = "updated streaming thought"
        val block = AssistantBlock(BlockKind.Thinking, text = initial, textStreaming = true)

        setContent {
            PiTheme {
                ThinkingDetail(
                    block = block,
                    textAtOpen = initial,
                    isStreaming = true,
                )
            }
        }
        onNodeWithTag(STREAMING_THINKING_TEXT_TEST_TAG).assertTextEquals(initial)

        mainClock.autoAdvance = false
        runOnIdle { block.text = updated }
        onNodeWithTag(STREAMING_THINKING_TEXT_TEST_TAG).assertTextEquals(initial)

        mainClock.advanceTimeBy(100)
        waitForIdle()
        onNodeWithTag(STREAMING_THINKING_TEXT_TEST_TAG).assertTextEquals(updated)
        mainClock.autoAdvance = true
    }

    @Test
    fun completionImmediatelySwitchesFinalTextToMarkdown() = runComposeUiTest {
        val initial = "unfinished **streaming** thought"
        val finalHeading = "Final rendered thought"
        val block = AssistantBlock(BlockKind.Thinking, text = initial, textStreaming = true)

        setContent {
            PiTheme {
                ThinkingDetail(
                    block = block,
                    textAtOpen = initial,
                    isStreaming = block.textStreaming,
                )
            }
        }
        onNodeWithTag(STREAMING_THINKING_TEXT_TEST_TAG).assertTextEquals(initial)

        runOnIdle {
            block.text = "# $finalHeading"
            block.textStreaming = false
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(finalHeading).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(STREAMING_THINKING_TEXT_TEST_TAG).assertDoesNotExist()
        onNodeWithText(finalHeading).assertExists()
    }
}

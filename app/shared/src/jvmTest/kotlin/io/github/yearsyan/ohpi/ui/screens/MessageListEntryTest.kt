package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.chat.AssistantBlock
import io.github.yearsyan.ohpi.chat.BlockKind
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.i18n.S
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Regression tests for entering a non-empty session: the timeline must land
 * at the bottom even though the composer height (the bottom content padding)
 * only resolves a frame after the first layout, and when switching between
 * already-attached sessions in the wide layout.
 */
@OptIn(ExperimentalTestApi::class)
class MessageListEntryTest {
    private fun testController(scope: CoroutineScope): ChatController =
        ChatController(
            scope = scope,
            gateway = "ws://localhost",
            token = "",
            onToast = { _, _ -> },
            onSessionReady = { _, _, _, _ -> },
            onSessionNameChanged = { _, _ -> },
            onStreamingChanged = { _, _ -> },
            strings = {
                ChatController.ChatStrings(
                    commandRejected = "",
                    abortSent = "",
                    compacting = "",
                    compacted = "",
                    retryOk = "",
                    retryFailed = "",
                    agentDone = "",
                    retrying = "",
                    notify = "",
                    modelOptionsFailed = { "" },
                )
            },
            loadCapabilities = { error("not used in tests") },
        )

    /** Alternating user/status singles, enough to overflow any test viewport. */
    private fun fillConversation(controller: ChatController, tag: String, count: Int = 60) {
        controller.items.clear()
        var key = 1L
        repeat(count) { i ->
            if (i % 2 == 0) {
                controller.items.add(TimelineItem.UserItem(key++, "user-$tag-$i", ts = i.toLong()))
            } else {
                controller.items.add(TimelineItem.StatusItem(key++, "status-$tag-$i", ts = i.toLong()))
            }
        }
    }

    @Test
    fun enteringNonEmptySessionStaysAtBottomWhenBottomPaddingResolvesLate() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        var bottomPadding by mutableStateOf(0.dp)
        lateinit var scrollToBottomDesc: String
        setContent {
            scrollToBottomDesc = S.scrollToBottom
            MessageList(controller = controller, bottomPadding = bottomPadding, scrollToBottomTick = 0)
        }
        waitForIdle()
        onNodeWithText("status-a-59").assertIsDisplayed()

        // the composer reports its real height a frame after entry
        runOnIdle { bottomPadding = 220.dp }
        waitForIdle()

        onNodeWithText("status-a-59").assertIsDisplayed()
        onNodeWithContentDescription(scrollToBottomDesc).assertDoesNotExist()
    }

    @Test
    fun switchingToAnotherNonEmptySessionJumpsToItsBottom() = runComposeUiTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val a = testController(scope).also { fillConversation(it, "a") }
        val b = testController(scope).also { fillConversation(it, "b") }
        // same group count and same per-controller key sequence on purpose:
        // count/signature based effects must not be the only repositioning
        var shown by mutableStateOf(a)
        lateinit var scrollToBottomDesc: String
        setContent {
            scrollToBottomDesc = S.scrollToBottom
            MessageList(controller = shown, bottomPadding = 0.dp, scrollToBottomTick = 0)
        }
        waitForIdle()
        onNodeWithText("status-a-59").assertIsDisplayed()

        // the user scrolls towards older messages in session A
        onRoot().performTouchInput { repeat(6) { swipeDown() } }
        waitForIdle()
        onNodeWithText("status-a-59").assertDoesNotExist()

        runOnIdle { shown = b }
        waitForIdle()

        onNodeWithText("status-b-59").assertIsDisplayed()
        onNodeWithContentDescription(scrollToBottomDesc).assertDoesNotExist()
    }

    @Test
    fun userScrollAwayDisablesFollowingUntilBackAtBottom() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        lateinit var scrollToBottomDesc: String
        setContent {
            scrollToBottomDesc = S.scrollToBottom
            MessageList(controller = controller, bottomPadding = 0.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        onRoot().performTouchInput { repeat(6) { swipeDown() } }
        waitForIdle()
        onNodeWithContentDescription(scrollToBottomDesc).assertIsDisplayed()

        // new content must not yank the list while the user is reading history
        val topBefore = "status-a-5"
        onNodeWithText(topBefore).assertIsDisplayed()
        runOnIdle {
            controller.items.add(TimelineItem.StatusItem(1000, "status-a-late", ts = 60L))
        }
        waitForIdle()
        onNodeWithText("status-a-late").assertDoesNotExist()
        onNodeWithText(topBefore).assertIsDisplayed()
    }

    @Test
    fun smallDragInsideNearBottomThresholdStillDisablesFollowing() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        setContent {
            MessageList(controller = controller, bottomPadding = 0.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        // Stay well inside the 96dp near-bottom threshold. The old behavior
        // cleared user intent here, so the next update snapped back and fought
        // the drag.
        onRoot().performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 40f, durationMillis = 200L)
        }
        waitForIdle()
        runOnIdle {
            controller.items.add(TimelineItem.StatusItem(1000, "status-a-near-tail", ts = 60L))
        }
        waitForIdle()

        onNodeWithText("status-a-near-tail").assertDoesNotExist()
    }

    @Test
    fun expandingThinkingSuspendsFollowingWhileItsHeightChanges() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        val thinking = AssistantBlock(BlockKind.Thinking, text = "short reasoning")
        val assistant = TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
            it.blocks.add(thinking)
        }
        controller.items.add(assistant)
        lateinit var processSummary: String
        setContent {
            processSummary = S.processThoughtOnce
            MessageList(controller = controller, bottomPadding = 0.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        onNodeWithText(processSummary).performClick()
        waitForIdle()
        onNodeWithText(processSummary).assertIsDisplayed()

        // A live reasoning update can make the expanded block much taller.
        // The header must stay anchored instead of being yanked to the tail.
        runOnIdle {
            thinking.text = (1..120).joinToString("\n") { "reasoning line $it" }
        }
        waitForIdle()

        onNodeWithText(processSummary).assertIsDisplayed()
    }
}

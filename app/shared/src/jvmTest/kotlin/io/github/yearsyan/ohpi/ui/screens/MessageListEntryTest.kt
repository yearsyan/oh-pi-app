package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import io.github.yearsyan.ohpi.ui.components.AGENT_PROCESS_BLOCK_TEST_TAG
import io.github.yearsyan.ohpi.ui.components.isWithinBottomThreshold
import io.github.yearsyan.ohpi.ui.components.scrollToBottom
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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

        onNodeWithContentDescription(scrollToBottomDesc).performClick()
        waitForIdle()

        onNodeWithText("status-a-late").assertIsDisplayed()
        onNodeWithContentDescription(scrollToBottomDesc).assertDoesNotExist()
    }

    @Test
    fun scrollButtonFollowsTailThatGrowsAfterItIsFirstComposed() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        lateinit var scrollToBottomDesc: String
        val finalMarker = "final deferred Markdown tail marker"
        val longMarkdown =
            (1..40).joinToString("\n\n") { "deferred Markdown paragraph $it" } +
                "\n\n$finalMarker"
        setContent {
            scrollToBottomDesc = S.scrollToBottom
            MessageList(controller = controller, bottomPadding = 220.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        // Keep the tail out of composition so Markdown parsing only starts
        // after the button jumps to the newly appended assistant run.
        onRoot().performTouchInput { repeat(6) { swipeDown() } }
        runOnIdle {
            controller.items.add(
                TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
                    it.blocks.add(AssistantBlock(BlockKind.Thinking, text = "deferred reasoning"))
                    it.blocks.add(AssistantBlock(BlockKind.Text, text = longMarkdown))
                },
            )
        }
        waitForIdle()
        onNodeWithText(finalMarker).assertDoesNotExist()

        onNodeWithContentDescription(scrollToBottomDesc).performClick()
        waitForIdle()

        onNodeWithText(finalMarker).assertIsDisplayed()
        onNodeWithContentDescription(scrollToBottomDesc).assertDoesNotExist()
    }

    @Test
    fun smallDragInsideNearBottomThresholdStillDisablesFollowing() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        var dragDistancePx = 0f
        setContent {
            dragDistancePx =
                LocalViewConfiguration.current.touchSlop +
                    with(LocalDensity.current) { 40.dp.toPx() }
            MessageList(controller = controller, bottomPadding = 0.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        // Move 40dp after touch slop: outside the 16dp attachment zone but well
        // inside the independent 96dp streaming-pinned threshold. Following
        // must remain disabled so the next update does not fight the drag.
        onRoot().performTouchInput {
            swipeDown(
                startY = centerY,
                endY = centerY + dragDistancePx,
                durationMillis = 1_000L,
            )
        }
        waitForIdle()
        runOnIdle {
            controller.items.add(TimelineItem.StatusItem(1000, "status-a-near-tail", ts = 60L))
        }
        waitForIdle()

        onNodeWithText("status-a-near-tail").assertDoesNotExist()
    }

    @Test
    fun userDragRequestsKeyboardDismissOncePerGesture() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        var dismissRequests = 0
        setContent {
            MessageList(
                controller = controller,
                bottomPadding = 0.dp,
                scrollToBottomTick = 0,
                onKeyboardDismissRequested = { dismissRequests++ },
            )
        }
        waitForIdle()

        onRoot().performTouchInput { swipeDown() }
        waitForIdle()
        runOnIdle { assertEquals(1, dismissRequests) }

        onRoot().performTouchInput { swipeDown() }
        waitForIdle()
        runOnIdle { assertEquals(2, dismissRequests) }
    }

    @Test
    fun enteringBottomAttachmentThresholdKeepsFollowingAndHidesButton() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        lateinit var listState: LazyListState
        var attachmentThresholdPx = 0
        var nearBottomGapPx = 0
        lateinit var scrollToBottomDesc: String
        setContent {
            listState = rememberLazyListState()
            attachmentThresholdPx = with(LocalDensity.current) { 16.dp.roundToPx() }
            nearBottomGapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
            scrollToBottomDesc = S.scrollToBottom
            MessageList(
                controller = controller,
                bottomPadding = 0.dp,
                scrollToBottomTick = 0,
                listStateOverride = listState,
            )
        }
        waitForIdle()
        val exactBottomFirstIndex = listState.firstVisibleItemIndex
        val exactBottomFirstOffset = listState.firstVisibleItemScrollOffset

        // Establish real user intent outside the attachment zone first.
        onRoot().performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 56f, durationMillis = 200L)
        }
        waitForIdle()
        onNodeWithContentDescription(scrollToBottomDesc).assertIsDisplayed()

        // Move to exactly 8dp from the end without passing through the exact
        // bottom. Entering the 16dp zone must clear the away state and hide the
        // affordance.
        runBlocking {
            listState.scrollToItem(
                index = exactBottomFirstIndex,
                scrollOffset = (exactBottomFirstOffset - nearBottomGapPx).coerceAtLeast(0),
            )
        }
        waitForIdle()
        runOnIdle {
            assertTrue(listState.isWithinBottomThreshold(attachmentThresholdPx))
        }
        onNodeWithContentDescription(scrollToBottomDesc).assertDoesNotExist()

        runOnIdle {
            repeat(8) { index ->
                controller.items.add(
                    TimelineItem.StatusItem(
                        key = 1000L + index,
                        text = "attached-tail-$index",
                        ts = 60L + index,
                    ),
                )
            }
        }
        waitUntil(timeoutMillis = 15_000) {
            onAllNodesWithText("attached-tail-7").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("attached-tail-7").assertIsDisplayed()
        onNodeWithContentDescription(scrollToBottomDesc).assertDoesNotExist()
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

    @Test
    fun tapOnProcessHeaderLandsWhileDeltasKeepGrowingThePinnedTail() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        val detailCount = 20
        val growingText = AssistantBlock(BlockKind.Text, text = "first line")
        val assistant = TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
            repeat(detailCount) { index ->
                it.blocks.add(AssistantBlock(BlockKind.Thinking, text = "live reasoning ${index + 1}"))
            }
            it.blocks.add(growingText)
        }
        controller.items.add(assistant)
        lateinit var processSummary: String
        setContent {
            processSummary = S.processThoughtTimes(detailCount)
            MessageList(controller = controller, bottomPadding = 0.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        val headerBounds = onNodeWithText(processSummary).fetchSemanticsNode().boundsInRoot
        val headerPos = Offset(headerBounds.left + headerBounds.width / 4f, headerBounds.center.y)

        // Hold a finger on the header while a delta grows the tail. The
        // tail-follow scroll used to shift the row out from under the finger,
        // so the next tiny move was reported out-of-bounds and the tap was
        // cancelled (a real finger always emits small move events).
        mainClock.autoAdvance = false
        onRoot().performTouchInput { down(headerPos) }
        runOnIdle {
            growingText.text = (1..80).joinToString("\n") { "streamed line $it" }
        }
        // Let recomposition, the follow effects, and the relayout run while
        // the finger is still down (staying under the long-press timeout).
        repeat(10) { mainClock.advanceTimeByFrame() }
        onRoot().performTouchInput {
            moveBy(Offset(1f, 0f))
            up()
        }
        mainClock.autoAdvance = true
        waitForIdle()

        // the tap must have toggled the inline expansion
        onNodeWithText("live reasoning 1").assertIsDisplayed()
    }

    @Test
    fun expandingTailProcessKeepsItsBottomEdgeAnchoredDuringAnimation() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        val detailCount = 8
        val assistant = TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
            repeat(detailCount) { index ->
                it.blocks.add(
                    AssistantBlock(
                        BlockKind.Thinking,
                        text = "tail reasoning ${index + 1}",
                    ),
                )
            }
        }
        controller.items.add(assistant)
        lateinit var processSummary: String
        setContent {
            processSummary = S.processThoughtTimes(detailCount)
            MessageList(controller = controller, bottomPadding = 120.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        val processBlock = onNodeWithTag(AGENT_PROCESS_BLOCK_TEST_TAG)
        val collapsedBounds = processBlock.fetchSemanticsNode().boundsInRoot
        mainClock.autoAdvance = false
        onNodeWithText(processSummary).performClick()
        var sawGrowth = false
        repeat(12) {
            mainClock.advanceTimeByFrame()
            val frameBounds = processBlock.fetchSemanticsNode().boundsInRoot
            sawGrowth = sawGrowth || frameBounds.height > collapsedBounds.height
            assertTrue(
                abs(frameBounds.bottom - collapsedBounds.bottom) <= 1f,
                "every expansion frame must preserve the tail block's bottom edge",
            )
        }

        val expandingBounds = processBlock.fetchSemanticsNode().boundsInRoot
        assertTrue(
            sawGrowth,
            "the inline details must be partway through their expansion",
        )
        assertTrue(
            abs(expandingBounds.bottom - collapsedBounds.bottom) <= 1f,
            "a tail expansion started at the bottom must keep its bottom edge fixed",
        )
        assertTrue(
            expandingBounds.top < collapsedBounds.top,
            "the growing process block must push its top edge upward",
        )

        mainClock.autoAdvance = true
        waitForIdle()
        val expandedBounds = processBlock.fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(expandedBounds.bottom - collapsedBounds.bottom) <= 1f,
            "the final expanded height must preserve the same bottom edge",
        )
        onNodeWithText("tail reasoning $detailCount").assertIsDisplayed()
    }

    @Test
    fun collapsingAndReexpandingTailProcessKeepsItsBottomEdgeAnchored() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        val detailCount = 8
        controller.items.add(
            TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
                repeat(detailCount) { index ->
                    it.blocks.add(
                        AssistantBlock(
                            BlockKind.Thinking,
                            text = "reexpanded reasoning ${index + 1}",
                        ),
                    )
                }
            },
        )
        lateinit var processSummary: String
        setContent {
            processSummary = S.processThoughtTimes(detailCount)
            MessageList(controller = controller, bottomPadding = 120.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        val processBlock = onNodeWithTag(AGENT_PROCESS_BLOCK_TEST_TAG)
        onNodeWithText(processSummary).performClick()
        waitForIdle()
        onNodeWithText(processSummary).performClick()
        waitForIdle()

        val collapsedBounds = processBlock.fetchSemanticsNode().boundsInRoot
        mainClock.autoAdvance = false
        onNodeWithText(processSummary).performClick()
        repeat(6) { mainClock.advanceTimeByFrame() }

        val reexpandingBounds = processBlock.fetchSemanticsNode().boundsInRoot
        assertTrue(
            reexpandingBounds.height > collapsedBounds.height,
            "the inline details must be partway through their second expansion",
        )
        assertTrue(
            abs(reexpandingBounds.bottom - collapsedBounds.bottom) <= 1f,
            "a re-expanded tail process must keep the same bottom edge",
        )

        mainClock.autoAdvance = true
        waitForIdle()
        val expandedBounds = processBlock.fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(expandedBounds.bottom - collapsedBounds.bottom) <= 1f,
            "the completed second expansion must preserve the same bottom edge",
        )
    }

    @Test
    fun expandingTailProcessAwayFromBottomKeepsItsTopEdgeAnchored() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        val detailCount = 8
        controller.items.add(
            TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
                repeat(detailCount) { index ->
                    it.blocks.add(
                        AssistantBlock(
                            BlockKind.Thinking,
                            text = "away reasoning ${index + 1}",
                        ),
                    )
                }
            },
        )
        lateinit var processSummary: String
        lateinit var scrollToBottomDesc: String
        setContent {
            processSummary = S.processThoughtTimes(detailCount)
            scrollToBottomDesc = S.scrollToBottom
            MessageList(controller = controller, bottomPadding = 120.dp, scrollToBottomTick = 0)
        }
        waitForIdle()

        // Move slightly away from the exact bottom while leaving the tail
        // process header comfortably inside the viewport.
        onRoot().performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 56f, durationMillis = 200L)
        }
        waitForIdle()
        onNodeWithContentDescription(scrollToBottomDesc).assertIsDisplayed()

        val processBlock = onNodeWithTag(AGENT_PROCESS_BLOCK_TEST_TAG)
        val topBeforeExpansion = processBlock.fetchSemanticsNode().boundsInRoot.top
        onNodeWithText(processSummary).performClick()
        waitForIdle()
        val topAfterExpansion = processBlock.fetchSemanticsNode().boundsInRoot.top

        assertTrue(
            abs(topAfterExpansion - topBeforeExpansion) <= 1f,
            "an expansion started away from the bottom must keep its top edge fixed",
        )
    }

    @Test
    fun expandingProcessWithLaterContentKeepsItsBottomAnchored() = runComposeUiTest {
        mainClock.autoAdvance = true
        val controller = testController(CoroutineScope(Dispatchers.Default))
        fillConversation(controller, "a")
        val detailCount = 20
        val assistant = TimelineItem.AssistantItem(key = 1000, streaming = false, ts = 60L).also {
            repeat(detailCount) { index ->
                it.blocks.add(
                    AssistantBlock(
                        BlockKind.Thinking,
                        text = "middle reasoning ${index + 1}",
                    ),
                )
            }
            it.blocks.add(AssistantBlock(BlockKind.Text, text = "content after process"))
        }
        controller.items.add(assistant)
        lateinit var listState: LazyListState
        var attachmentThresholdPx = 0
        lateinit var processSummary: String
        setContent {
            listState = rememberLazyListState()
            attachmentThresholdPx = with(LocalDensity.current) { 16.dp.roundToPx() }
            processSummary = S.processThoughtTimes(detailCount)
            MessageList(
                controller = controller,
                bottomPadding = 0.dp,
                scrollToBottomTick = 0,
                listStateOverride = listState,
            )
        }
        waitForIdle()
        runBlocking {
            listState.scrollToBottom(listState.layoutInfo.totalItemsCount - 1)
        }
        waitForIdle()
        runOnIdle {
            assertTrue(listState.isWithinBottomThreshold(attachmentThresholdPx))
        }

        val processBlock = onNodeWithTag(AGENT_PROCESS_BLOCK_TEST_TAG)
        val collapsedBounds = processBlock.fetchSemanticsNode().boundsInRoot
        mainClock.autoAdvance = false
        onNodeWithText(processSummary).performClick()
        var sawGrowth = false
        repeat(12) {
            mainClock.advanceTimeByFrame()
            val frameBounds = processBlock.fetchSemanticsNode().boundsInRoot
            sawGrowth = sawGrowth || frameBounds.height > collapsedBounds.height
            assertTrue(
                abs(frameBounds.bottom - collapsedBounds.bottom) <= 1f,
                "every expansion frame must keep a process in the tail run anchored: " +
                    "collapsed=$collapsedBounds frame=$frameBounds",
            )
        }

        assertTrue(
            sawGrowth,
            "the process with later content must begin expanding",
        )
        val expandingBounds = processBlock.fetchSemanticsNode().boundsInRoot
        assertTrue(
            expandingBounds.top < collapsedBounds.top,
            "a bottom-anchored process with later content must grow upward",
        )

        mainClock.autoAdvance = true
        waitForIdle()
        val expandedBounds = processBlock.fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(expandedBounds.bottom - collapsedBounds.bottom) <= 1f,
            "the completed expansion must keep a process in the tail run anchored: " +
                "collapsed=$collapsedBounds expanded=$expandedBounds",
        )
    }

    @Test
    fun longPressOnTallSelectableMarkdownDoesNotRelocateList() = runComposeUiTest {
        val controller = testController(CoroutineScope(Dispatchers.Default))
        val firstParagraph = "selectable paragraph 1"
        val longMarkdown =
            (1..80).joinToString("\n\n") { index -> "selectable paragraph $index" }
        controller.items.add(
            TimelineItem.AssistantItem(key = 1, streaming = false, ts = 1L).also {
                it.blocks.add(AssistantBlock(BlockKind.Text, text = longMarkdown))
            },
        )
        lateinit var listState: LazyListState
        setContent {
            listState = rememberLazyListState()
            MessageList(
                controller = controller,
                bottomPadding = 0.dp,
                scrollToBottomTick = 0,
                listStateOverride = listState,
            )
        }
        waitForIdle()

        // Mark the list as user-detached, then place the oversized selectable block at its start.
        // Without the pointer-contact BringIntoViewSpec, SelectionContainer focus moves this anchor.
        onRoot().performTouchInput { swipeDown() }
        waitForIdle()
        runBlocking { listState.scrollToItem(0) }
        waitForIdle()
        onNodeWithText(firstParagraph).assertIsDisplayed()

        var indexBefore = 0
        var offsetBefore = 0
        runOnIdle {
            indexBefore = listState.firstVisibleItemIndex
            offsetBefore = listState.firstVisibleItemScrollOffset
        }

        onNodeWithText(firstParagraph).performTouchInput { longClick() }
        waitForIdle()

        runOnIdle {
            assertEquals(indexBefore, listState.firstVisibleItemIndex)
            assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
        }
    }
}

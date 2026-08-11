package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.AssistantBlock
import io.github.yearsyan.ohpi.chat.BlockKind
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.groupTimelineItems
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationNavigationTest {
    @Test
    fun wideLayoutCentersTranscriptAndLeavesSymmetricRails() {
        assertEquals(0.dp, conversationHorizontalInset(899.dp))
        assertEquals(70.dp, conversationHorizontalInset(900.dp))
        assertEquals(370.dp, conversationHorizontalInset(1500.dp))
    }

    @Test
    fun quickJumpTargetsUseTheFirstPromptInEachConsecutiveUserRun() {
        val firstAssistant = TimelineItem.AssistantItem(key = 1, streaming = false)
        val secondAssistant = TimelineItem.AssistantItem(key = 7, streaming = false)
        val groups =
            groupTimelineItems(
                listOf(
                    TimelineItem.StatusItem(key = 0, text = "connected", ts = 0),
                    TimelineItem.UserItem(key = 2, text = "first", ts = 0),
                    TimelineItem.UserItem(key = 3, text = "second", ts = 0),
                    firstAssistant,
                    TimelineItem.UserItem(key = 5, text = "third", ts = 0),
                    TimelineItem.UserItem(key = 6, text = "fourth", ts = 0),
                    secondAssistant,
                ),
            )

        assertEquals(listOf(1, 4), conversationQuickJumpTargets(groups))
    }

    @Test
    fun quickJumpWindowStaysCenteredOnTheActiveTurn() {
        val targets = (0..30).toList()

        assertEquals((0..4).toList(), conversationQuickJumpWindow(targets, 0, 5))
        assertEquals((13..17).toList(), conversationQuickJumpWindow(targets, 15, 5))
        assertEquals((26..30).toList(), conversationQuickJumpWindow(targets, 30, 5))
    }

    @Test
    fun quickJumpTapOnlySelectsMarkersInsideTheCenteredCluster() {
        val targets = listOf(10, 20, 30)

        assertEquals(null, conversationQuickJumpTargetAt(20f, 100f, 10f, targets))
        assertEquals(10, conversationQuickJumpTargetAt(40f, 100f, 10f, targets))
        assertEquals(20, conversationQuickJumpTargetAt(49f, 100f, 10f, targets))
        assertEquals(30, conversationQuickJumpTargetAt(60f, 100f, 10f, targets))
    }

    @Test
    fun quickJumpMarkersAreUniformUntilHoverMagnifiesTheLocalCluster() {
        assertEquals(9f, conversationQuickJumpMarkerWidthDp(4f, null, 1f))
        assertEquals(9f, conversationQuickJumpMarkerWidthDp(4f, 4f, 0f))
        assertEquals(38f, conversationQuickJumpMarkerWidthDp(4f, 4f, 1f))
        assertEquals(29f, conversationQuickJumpMarkerWidthDp(3f, 4f, 1f))
        assertEquals(20f, conversationQuickJumpMarkerWidthDp(2f, 4f, 1f))
        assertEquals(14f, conversationQuickJumpMarkerWidthDp(1f, 4f, 1f))
        assertEquals(9f, conversationQuickJumpMarkerWidthDp(0f, 4f, 1f))
    }

    @Test
    fun quickJumpMarkersKeepTheirLeftEdgeWhileGrowing() {
        val markerStartX =
            conversationQuickJumpMarkerStartX(
                railWidth = 40f,
                restingMarkerWidth = 9f,
            )

        assertEquals(15.5f, markerStartX)
        assertEquals(24.5f, markerStartX + 9f)
        assertEquals(53.5f, markerStartX + 38f)
    }

    @Test
    fun quickJumpPreviewContainsThePromptAndVisibleResponseForThatTurn() {
        val assistant = TimelineItem.AssistantItem(key = 2, streaming = false).also {
            it.blocks += AssistantBlock(BlockKind.Thinking, text = "hidden reasoning")
            it.blocks += AssistantBlock(BlockKind.Text, text = "## Done\n\n- first item")
        }
        val groups =
            groupTimelineItems(
                listOf(
                    TimelineItem.UserItem(key = 1, text = "Fix the rail", ts = 0),
                    assistant,
                    TimelineItem.UserItem(key = 3, text = "Next prompt", ts = 0),
                ),
            )

        assertEquals(
            ConversationQuickJumpPreview(
                prompt = "Fix the rail",
                response = "Done\n\n• first item",
            ),
            conversationQuickJumpPreview(groups, 0),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun singleQuickJumpTargetDoesNotShowTheRail() = runComposeUiTest {
        setContent {
            PiTheme {
                ConversationQuickJumpRail(
                    targets = listOf(0),
                    currentItemIndex = 0,
                    totalItemsCount = 1,
                    onJump = {},
                    modifier = Modifier.size(width = 48.dp, height = 200.dp),
                )
            }
        }

        onNodeWithTag(CONVERSATION_QUICK_JUMP_RAIL_TEST_TAG).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hoveringAMarkerShowsAndLeavingHidesItsConversationPreview() = runComposeUiTest {
        var jumpedTo: Int? = null
        setContent {
            PiTheme {
                ConversationQuickJumpRail(
                    targets = listOf(10, 20, 30),
                    currentItemIndex = 20,
                    totalItemsCount = 31,
                    previewForTarget = { target ->
                        ConversationQuickJumpPreview(
                            prompt = "prompt $target",
                            response = "response $target",
                        )
                    },
                    onJump = { jumpedTo = it },
                    modifier = Modifier.size(width = 48.dp, height = 200.dp),
                )
            }
        }

        val rail = onNodeWithTag(CONVERSATION_QUICK_JUMP_RAIL_TEST_TAG)
        rail.performMouseInput { enter(center) }
        onNodeWithText("prompt 20").assertIsDisplayed()
        onNodeWithText("response 20").assertIsDisplayed()
        rail.performMouseInput { click(center) }
        runOnIdle { assertEquals(20, jumpedTo) }

        rail.performMouseInput { exit(Offset(-1f, -1f)) }
        onNodeWithTag(CONVERSATION_QUICK_JUMP_PREVIEW_TEST_TAG).assertDoesNotExist()
    }
}

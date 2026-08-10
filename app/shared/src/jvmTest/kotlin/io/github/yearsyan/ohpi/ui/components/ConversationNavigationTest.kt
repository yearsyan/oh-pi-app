package io.github.yearsyan.ohpi.ui.components

import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.groupTimelineItems
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
}

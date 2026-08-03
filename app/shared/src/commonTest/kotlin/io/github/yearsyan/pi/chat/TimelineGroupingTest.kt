package io.github.yearsyan.pi.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimelineGroupingTest {
    @Test
    fun adjacentAssistantAndToolItemsBecomeOneRun() {
        val user = TimelineItem.UserItem(1L, "question", 1L)
        val firstAssistant = TimelineItem.AssistantItem(2L)
        val tool = TimelineItem.ToolItem(3L, ToolCallView(name = "read"))
        val secondAssistant = TimelineItem.AssistantItem(4L)
        val status = TimelineItem.StatusItem(5L, "done", ts = 5L)
        val finalAssistant = TimelineItem.AssistantItem(6L)

        val groups =
            groupTimelineItems(
                listOf(user, firstAssistant, tool, secondAssistant, status, finalAssistant),
            )

        assertEquals(4, groups.size)
        assertIs<TimelineRenderGroup.Single>(groups[0])
        val run = assertIs<TimelineRenderGroup.AssistantRun>(groups[1])
        assertEquals(2L, run.key)
        assertEquals(listOf(firstAssistant, tool, secondAssistant), run.items)
        assertIs<TimelineRenderGroup.Single>(groups[2])
        assertEquals(listOf(finalAssistant), assertIs<TimelineRenderGroup.AssistantRun>(groups[3]).items)
    }
}

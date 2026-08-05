package io.github.yearsyan.ohpi.chat

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

    @Test
    fun visibleTextSplitsConsecutiveProcessChunks() {
        val first = TimelineItem.AssistantItem(1L).apply {
            blocks += AssistantBlock(BlockKind.Thinking, "think 1")
            blocks += AssistantBlock(BlockKind.ToolCall, tool = ToolCallView(name = "read"))
            blocks += AssistantBlock(BlockKind.Text, "output 1")
        }
        val second = TimelineItem.AssistantItem(2L).apply {
            blocks += AssistantBlock(BlockKind.Thinking, "think 2")
            blocks += AssistantBlock(BlockKind.ToolCall, tool = ToolCallView(name = "write"))
            blocks += AssistantBlock(BlockKind.Text, "output 2")
        }

        val chunks = chunkAssistantRun(listOf(first, second))

        assertEquals(4, chunks.size)
        assertEquals(2, assertIs<AssistantRenderChunk.Process>(chunks[0]).details.size)
        assertEquals("output 1", assertIs<AssistantRenderChunk.Text>(chunks[1]).block.text)
        assertEquals(2, assertIs<AssistantRenderChunk.Process>(chunks[2]).details.size)
        assertEquals("output 2", assertIs<AssistantRenderChunk.Text>(chunks[3]).block.text)
    }
}

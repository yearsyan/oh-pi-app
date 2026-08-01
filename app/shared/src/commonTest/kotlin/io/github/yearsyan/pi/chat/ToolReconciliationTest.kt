package io.github.yearsyan.pi.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ToolReconciliationTest {
    @Test
    fun standaloneExecutionIsMergedIntoAssistantTool() {
        val embedded = ToolCallView(
            id = "call-1",
            name = "bash",
            args = "{\"command\":\"pwd\"}",
            argsDone = true,
            state = ToolState.Pending,
        )
        val standalone = ToolCallView(
            id = "call-1",
            name = "bash",
            output = "/tmp",
            outputDone = true,
            state = ToolState.Done,
            startedAt = 10L,
            endedAt = 20L,
        )
        val assistant = TimelineItem.AssistantItem(key = 1L).apply {
            blocks.add(AssistantBlock(BlockKind.ToolCall, tool = embedded))
        }
        val items = mutableListOf<TimelineItem>(
            assistant,
            TimelineItem.ToolItem(key = 2L, tool = standalone),
        )

        reconcileStandaloneTool(items, embedded)

        assertEquals(1, items.size)
        assertSame(assistant, items.single())
        assertEquals("/tmp", embedded.output)
        assertEquals(ToolState.Done, embedded.state)
        assertEquals(10L, embedded.startedAt)
        assertEquals(20L, embedded.endedAt)
    }

    @Test
    fun lateRunningUpdateDoesNotRegressCompletedTool() {
        val completed = ToolCallView(
            id = "call-1",
            output = "done",
            outputDone = true,
            state = ToolState.Done,
            startedAt = 10L,
            endedAt = 20L,
        )
        val running = ToolCallView(
            id = "call-1",
            output = "partial",
            state = ToolState.Running,
            startedAt = 11L,
        )

        completed.absorbExecutionFrom(running)

        assertEquals(ToolState.Done, completed.state)
        assertEquals("done", completed.output)
        assertEquals(10L, completed.startedAt)
        assertEquals(20L, completed.endedAt)
        assertFalse(completed.isError)
    }
}

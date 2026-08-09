package io.github.yearsyan.ohpi.ui.screens

import io.github.yearsyan.ohpi.chat.AssistantBlock
import io.github.yearsyan.ohpi.chat.BlockKind
import io.github.yearsyan.ohpi.chat.TimelineItem
import io.github.yearsyan.ohpi.chat.ToolCallView
import io.github.yearsyan.ohpi.chat.groupTimelineItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TailFollowSignatureTest {
    private fun signatureOf(vararg items: TimelineItem): TailFollowSignature? =
        tailFollowSignature(groupTimelineItems(items.toList()).lastOrNull())

    @Test
    fun collapsedThinkingGrowthDoesNotChangeSignature() {
        val thinking = AssistantBlock(BlockKind.Thinking, text = "first")
        val assistant = TimelineItem.AssistantItem(key = 1).also { it.blocks += thinking }
        val before = signatureOf(assistant)

        thinking.text += " second"

        assertEquals(before, signatureOf(assistant))
    }

    @Test
    fun collapsedToolOutputGrowthDoesNotChangeSignature() {
        val tool = ToolCallView(name = "bash", output = "first")
        val item = TimelineItem.ToolItem(key = 1, tool = tool)
        val before = signatureOf(item)

        tool.output += " second"

        assertEquals(before, signatureOf(item))
    }

    @Test
    fun consecutiveProcessDetailsRemainOneVisibleChunk() {
        val assistant = TimelineItem.AssistantItem(key = 1).also {
            it.blocks += AssistantBlock(BlockKind.Thinking, text = "reasoning")
        }
        val before = signatureOf(assistant)

        assistant.blocks += AssistantBlock(
            BlockKind.ToolCall,
            tool = ToolCallView(name = "bash"),
        )

        assertEquals(before, signatureOf(assistant))
    }

    @Test
    fun visibleTextGrowthAndNewVisibleChunkChangeSignature() {
        val text = AssistantBlock(BlockKind.Text, text = "first")
        val assistant = TimelineItem.AssistantItem(key = 1).also { it.blocks += text }
        val initial = signatureOf(assistant)

        text.text += " second"
        val grownText = signatureOf(assistant)
        assertNotEquals(initial, grownText)

        assistant.blocks += AssistantBlock(
            BlockKind.ToolCall,
            tool = ToolCallView(name = "bash"),
        )
        assertNotEquals(grownText, signatureOf(assistant))
    }
}

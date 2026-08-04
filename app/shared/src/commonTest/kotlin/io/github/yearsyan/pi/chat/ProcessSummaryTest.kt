package io.github.yearsyan.pi.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessSummaryTest {
    private fun thinkingDetail() = AssistantProcessDetail.Thinking(AssistantBlock(BlockKind.Thinking, "…"))

    private fun toolDetail(name: String, args: String = "{}") =
        AssistantProcessDetail.Tool(ToolCallView(name = name, args = args))

    @Test
    fun singleThinkingWithoutToolsIsLoneThinking() {
        val summary = summarizeProcessDetails(listOf(thinkingDetail()))
        assertEquals(1, summary.thinkingCount)
        assertEquals(0, summary.toolActionCount)
    }

    @Test
    fun countsThinkingAndGroupsToolKinds() {
        val summary =
            summarizeProcessDetails(
                listOf(
                    thinkingDetail(),
                    thinkingDetail(),
                    toolDetail("read", """{"path":"a.kt"}"""),
                    toolDetail("write", """{"path":"b.kt"}"""),
                    toolDetail("bash", """{"command":"ls"}"""),
                ),
            )
        assertEquals(2, summary.thinkingCount)
        assertEquals(1, summary.filesRead)
        assertEquals(1, summary.filesWritten)
        assertEquals(1, summary.commandsRun)
        assertEquals(0, summary.searches)
        assertEquals(0, summary.otherToolCalls)
        assertEquals(3, summary.toolActionCount)
    }

    @Test
    fun readAndWriteActionsAreCountedPerDistinctFile() {
        val summary =
            summarizeProcessDetails(
                listOf(
                    toolDetail("read", """{"path":"a.kt"}"""),
                    toolDetail("read", """{"path":"a.kt"}"""),
                    toolDetail("read", """{"path":"b.kt"}"""),
                    toolDetail("write", """{"path":"c.kt"}"""),
                    toolDetail("edit", """{"path":"c.kt"}"""),
                ),
            )
        assertEquals(2, summary.filesRead)
        assertEquals(1, summary.filesWritten)
    }

    @Test
    fun unknownToolsFallIntoOtherToolCalls() {
        val summary =
            summarizeProcessDetails(
                listOf(
                    toolDetail("browser"),
                    toolDetail("browser"),
                    toolDetail("grep", """{"pattern":"foo"}"""),
                    toolDetail("ls", """{"path":"."}"""),
                ),
            )
        assertEquals(2, summary.otherToolCalls)
        assertEquals(1, summary.searches)
        assertEquals(1, summary.directoryListings)
        assertEquals(4, summary.toolActionCount)
    }
}

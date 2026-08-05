package io.github.yearsyan.ohpi.ui.components

import io.github.yearsyan.ohpi.chat.ProcessSummary
import io.github.yearsyan.ohpi.i18n.EnStrings
import io.github.yearsyan.ohpi.i18n.ZhStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletedProcessSummaryTest {
    @Test
    fun thinkingIsAlwaysDescribedLast() {
        assertEquals(
            "写入 1 个文件，进行了思考",
            completedProcessSummary(ZhStrings, ProcessSummary(thinkingCount = 1, filesWritten = 1)),
        )
        assertEquals(
            "读取 1 个文件，思考 2 次",
            completedProcessSummary(ZhStrings, ProcessSummary(thinkingCount = 2, filesRead = 1)),
        )
        assertEquals(
            "wrote 1 file, read 2 files, thought",
            completedProcessSummary(
                EnStrings,
                ProcessSummary(thinkingCount = 1, filesWritten = 1, filesRead = 2),
            ),
        )
    }

    @Test
    fun singleFileSummaryShowsFileNameInsteadOfCount() {
        assertEquals(
            "写入 server.go，进行了思考",
            completedProcessSummary(
                ZhStrings,
                ProcessSummary(
                    thinkingCount = 1,
                    filesWritten = 1,
                    writtenFiles = listOf("/home/dev/workspace/ohpi/internal/gateway/server.go"),
                ),
            ),
        )
        assertEquals(
            "read index.ts, thought",
            completedProcessSummary(
                EnStrings,
                ProcessSummary(
                    thinkingCount = 1,
                    filesRead = 1,
                    readFiles = listOf("shared/src/components/index.ts"),
                ),
            ),
        )
    }

    @Test
    fun multipleFilesKeepCountSummary() {
        assertEquals(
            "写入 2 个文件，进行了思考",
            completedProcessSummary(
                ZhStrings,
                ProcessSummary(
                    thinkingCount = 1,
                    filesWritten = 2,
                    writtenFiles = listOf("a.kt", "b.kt"),
                ),
            ),
        )
    }

    @Test
    fun singleThinkingWithoutToolsUsesThoughtOnce() {
        assertEquals("进行了思考", completedProcessSummary(ZhStrings, ProcessSummary(thinkingCount = 1)))
        assertEquals("thought", completedProcessSummary(EnStrings, ProcessSummary(thinkingCount = 1)))
    }

    @Test
    fun repeatedThinkingWithoutToolsUsesCount() {
        assertEquals("思考 2 次", completedProcessSummary(ZhStrings, ProcessSummary(thinkingCount = 2)))
    }

    @Test
    fun toolsOnlyHaveNoThinkingPart() {
        assertEquals(
            "执行 1 条命令，搜索 2 次",
            completedProcessSummary(ZhStrings, ProcessSummary(commandsRun = 1, searches = 2)),
        )
    }
}

package io.github.yearsyan.pi.chat

/**
 * Aggregated counts of what happened inside one agent process block, used to
 * render a compact summary once the block is complete. Read/write actions are
 * counted per distinct target file; other actions are counted per call.
 */
internal data class ProcessSummary(
    val thinkingCount: Int = 0,
    val filesWritten: Int = 0,
    val filesRead: Int = 0,
    val commandsRun: Int = 0,
    val searches: Int = 0,
    val directoryListings: Int = 0,
    val otherToolCalls: Int = 0,
) {
    val toolActionCount: Int
        get() = filesWritten + filesRead + commandsRun + searches + directoryListings + otherToolCalls
}

internal fun summarizeProcessDetails(details: List<AssistantProcessDetail>): ProcessSummary {
    var thinkingCount = 0
    val writtenTargets = LinkedHashSet<String>()
    val readTargets = LinkedHashSet<String>()
    var commandsRun = 0
    var searches = 0
    var directoryListings = 0
    var otherToolCalls = 0

    details.forEach { detail ->
        when (detail) {
            is AssistantProcessDetail.Thinking -> thinkingCount++
            is AssistantProcessDetail.Tool -> {
                val action = toolAction(detail.tool.name, detail.tool.args)
                when (action.kind) {
                    ToolActionKind.Read -> readTargets += action.target
                    ToolActionKind.Write, ToolActionKind.Edit -> writtenTargets += action.target
                    ToolActionKind.Execute -> commandsRun++
                    ToolActionKind.Search -> searches++
                    ToolActionKind.List -> directoryListings++
                    ToolActionKind.Call -> otherToolCalls++
                }
            }
        }
    }

    return ProcessSummary(
        thinkingCount = thinkingCount,
        filesWritten = writtenTargets.size,
        filesRead = readTargets.size,
        commandsRun = commandsRun,
        searches = searches,
        directoryListings = directoryListings,
        otherToolCalls = otherToolCalls,
    )
}

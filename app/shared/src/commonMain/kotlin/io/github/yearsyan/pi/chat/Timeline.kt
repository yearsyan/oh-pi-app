package io.github.yearsyan.pi.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ToolState { Streaming, Pending, Running, Done, Error }

private fun ToolState.progressRank(): Int = when (this) {
    ToolState.Streaming -> 0
    ToolState.Pending -> 1
    ToolState.Running -> 2
    ToolState.Done -> 3
    ToolState.Error -> 4
}

/** Mutable view model for one tool call; fields update in place while streaming. */
class ToolCallView(
    id: String = "",
    name: String = "",
    args: String = "",
    argsDone: Boolean = false,
    output: String = "",
    outputDone: Boolean = false,
    state: ToolState = ToolState.Streaming,
    isError: Boolean = false,
    startedAt: Long = 0L,
    endedAt: Long? = null,
) {
    var id by mutableStateOf(id)
    var name by mutableStateOf(name)
    var args by mutableStateOf(args)
    var argsDone by mutableStateOf(argsDone)
    var output by mutableStateOf(output)
    var outputDone by mutableStateOf(outputDone)
    var state by mutableStateOf(state)
    var isError by mutableStateOf(isError)
    var startedAt by mutableStateOf(startedAt)
    var endedAt by mutableStateOf(endedAt)
}

internal fun ToolCallView.absorbExecutionFrom(source: ToolCallView) {
    val sourceIsAtLeastAsComplete = source.state.progressRank() >= state.progressRank()

    if (id.isBlank()) id = source.id
    if (name.isBlank()) name = source.name
    if (args.isBlank()) args = source.args
    argsDone = argsDone || source.argsDone

    if ((source.output.isNotEmpty() || source.outputDone) &&
        (output.isEmpty() || sourceIsAtLeastAsComplete)
    ) {
        output = source.output
    }
    outputDone = outputDone || source.outputDone

    if (sourceIsAtLeastAsComplete) state = source.state
    isError = isError || source.isError || state == ToolState.Error

    if (source.startedAt > 0L && (startedAt == 0L || sourceIsAtLeastAsComplete)) {
        startedAt = source.startedAt
    }
    source.endedAt?.let { sourceEndedAt ->
        val currentEndedAt = endedAt
        if (currentEndedAt == null || sourceEndedAt >= currentEndedAt) endedAt = sourceEndedAt
    }
}

enum class BlockKind { Thinking, Text, ToolCall }

class AssistantBlock(
    kind: BlockKind,
    text: String = "",
    tool: ToolCallView? = null,
) {
    var kind by mutableStateOf(kind)
    var text by mutableStateOf(text)
    var tool by mutableStateOf(tool)
}

sealed class TimelineItem {
    abstract val key: Long

    class UserItem(
        override val key: Long,
        text: String,
        ts: Long,
        sourceId: String? = null,
    ) : TimelineItem() {
        var text by mutableStateOf(text)
        var ts by mutableStateOf(ts)
        var sourceId by mutableStateOf(sourceId)
    }

    class AssistantItem(
        override val key: Long,
        model: String? = null,
        stopReason: String? = null,
        streaming: Boolean = true,
        ts: Long = 0L,
    ) : TimelineItem() {
        val blocks = mutableStateListOf<AssistantBlock>()
        var model by mutableStateOf(model)
        var stopReason by mutableStateOf(stopReason)
        var streaming by mutableStateOf(streaming)
        var ts by mutableStateOf(ts)
    }

    class ToolItem(
        override val key: Long,
        val tool: ToolCallView,
    ) : TimelineItem()

    class StatusItem(
        override val key: Long,
        text: String,
        tone: Tone = Tone.Info,
        ts: Long,
        val bashId: String? = null,
    ) : TimelineItem() {
        enum class Tone { Info, Warn, Error }

        var text by mutableStateOf(text)
        var tone by mutableStateOf(tone)
        var ts by mutableStateOf(ts)
    }
}

internal sealed interface TimelineRenderGroup {
    val key: Long

    data class Single(val item: TimelineItem) : TimelineRenderGroup {
        override val key: Long = item.key
    }

    data class AssistantRun(
        override val key: Long,
        val items: List<TimelineItem>,
    ) : TimelineRenderGroup
}

/** Coalesces adjacent assistant messages and tool executions into one rendered agent run. */
internal fun groupTimelineItems(items: List<TimelineItem>): List<TimelineRenderGroup> {
    val groups = mutableListOf<TimelineRenderGroup>()
    var assistantRun = mutableListOf<TimelineItem>()

    fun flushAssistantRun() {
        if (assistantRun.isEmpty()) return
        groups += TimelineRenderGroup.AssistantRun(
            key = assistantRun.first().key,
            items = assistantRun,
        )
        assistantRun = mutableListOf()
    }

    items.forEach { item ->
        when (item) {
            is TimelineItem.AssistantItem, is TimelineItem.ToolItem -> assistantRun += item
            else -> {
                flushAssistantRun()
                groups += TimelineRenderGroup.Single(item)
            }
        }
    }
    flushAssistantRun()
    return groups
}

internal sealed interface AssistantProcessDetail {
    data class Thinking(val block: AssistantBlock) : AssistantProcessDetail

    data class Tool(val tool: ToolCallView) : AssistantProcessDetail
}

internal sealed interface AssistantRenderChunk {
    data class Process(val details: List<AssistantProcessDetail>) : AssistantRenderChunk

    data class Text(val block: AssistantBlock) : AssistantRenderChunk
}

/** Merges only consecutive reasoning/tool details; visible text always starts a new chunk. */
internal fun chunkAssistantRun(items: List<TimelineItem>): List<AssistantRenderChunk> {
    val chunks = mutableListOf<AssistantRenderChunk>()
    var processDetails = mutableListOf<AssistantProcessDetail>()

    fun flushProcess() {
        if (processDetails.isEmpty()) return
        chunks += AssistantRenderChunk.Process(processDetails)
        processDetails = mutableListOf()
    }

    items.forEach { item ->
        when (item) {
            is TimelineItem.AssistantItem ->
                item.blocks.forEach { block ->
                    when (block.kind) {
                        BlockKind.Thinking -> processDetails += AssistantProcessDetail.Thinking(block)
                        BlockKind.ToolCall -> block.tool?.let {
                            processDetails += AssistantProcessDetail.Tool(it)
                        }
                        BlockKind.Text -> if (block.text.isNotBlank()) {
                            flushProcess()
                            chunks += AssistantRenderChunk.Text(block)
                        }
                    }
                }
            is TimelineItem.ToolItem -> processDetails += AssistantProcessDetail.Tool(item.tool)
            else -> Unit
        }
    }
    flushProcess()
    return chunks
}

internal fun reconcileStandaloneTool(
    items: MutableList<TimelineItem>,
    target: ToolCallView,
) {
    if (target.id.isBlank()) return

    for (item in items) {
        val standalone = item as? TimelineItem.ToolItem ?: continue
        if (standalone.tool !== target && standalone.tool.id == target.id) {
            target.absorbExecutionFrom(standalone.tool)
        }
    }
    for (index in items.lastIndex downTo 0) {
        val standalone = items[index] as? TimelineItem.ToolItem ?: continue
        if (standalone.tool !== target && standalone.tool.id == target.id) {
            items.removeAt(index)
        }
    }
}

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val thinkingLevels: List<String> = emptyList(),
) {
    val label: String get() = name.ifBlank { id }
    val qualified: String get() = if (provider.isNotBlank()) "$provider/$id" else id
}

data class UiDialogRequest(
    val id: String,
    val method: String,
    val title: String,
    val message: String,
    val options: List<String>,
    val placeholder: String,
    val prefill: String,
)

data class Toast(val id: Long, val text: String, val kind: Kind) {
    enum class Kind { Info, Error, Success }
}

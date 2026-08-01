package io.github.yearsyan.pi.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ToolState { Streaming, Pending, Running, Done, Error }

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
    ) : TimelineItem() {
        var text by mutableStateOf(text)
        var ts by mutableStateOf(ts)
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

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
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

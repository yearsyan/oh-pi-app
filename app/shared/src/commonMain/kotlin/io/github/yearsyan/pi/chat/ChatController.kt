package io.github.yearsyan.pi.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.net.PiClient
import io.github.yearsyan.pi.net.PiJson
import io.github.yearsyan.pi.net.argsToString
import io.github.yearsyan.pi.net.arr
import io.github.yearsyan.pi.net.bool
import io.github.yearsyan.pi.net.buildWsUrl
import io.github.yearsyan.pi.net.contentText
import io.github.yearsyan.pi.net.long
import io.github.yearsyan.pi.net.nowMillis
import io.github.yearsyan.pi.net.obj
import io.github.yearsyan.pi.net.parseMessage
import io.github.yearsyan.pi.net.plainText
import io.github.yearsyan.pi.net.str
import io.github.yearsyan.pi.net.strOrEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val InitialReconnectDelayMillis = 1_000L
internal const val MaxReconnectDelayMillis = 30_000L

internal fun reconnectDelayMillis(attempt: Int): Long {
    require(attempt > 0) { "attempt must be positive" }
    var delayMillis = InitialReconnectDelayMillis
    var remainingDoublings = attempt - 1
    while (remainingDoublings > 0 && delayMillis < MaxReconnectDelayMillis) {
        delayMillis = minOf(delayMillis * 2, MaxReconnectDelayMillis)
        remainingDoublings--
    }
    return delayMillis
}

/**
 * Owns one WebSocket connection to a pi2ws session and the reactive timeline
 * rendered by the chat UI. Mirrors the protocol handling of the web demo.
 */
class ChatController(
    private val scope: CoroutineScope,
    val gateway: String,
    val token: String,
    private val onToast: (String, Toast.Kind) -> Unit,
    private val onSessionReady: (sessionId: String, isNew: Boolean, workDir: String) -> Unit,
    private val onAutoName: (sessionId: String, title: String) -> Unit,
    private val strings: () -> ChatStrings,
) {
    /** Small string contract so the controller stays UI-independent. */
    data class ChatStrings(
        val newSessionCreated: String,
        val sessionAttached: String,
        val piCrashed: String,
        val connectionClosed: String,
        val commandRejected: String,
        val abortSent: String,
        val compacting: String,
        val compacted: String,
        val retryOk: String,
        val retryFailed: String,
        val agentDone: String,
        val turnStart: String,
        val notify: String,
    )

    var conn by mutableStateOf(ConnState.Disconnected); private set
    var sessionId by mutableStateOf(""); private set
    var workDir by mutableStateOf(""); private set
    var sessionName by mutableStateOf("")
    var model by mutableStateOf(""); private set
    var currentModel by mutableStateOf<ModelInfo?>(null); private set
    var thinkingLevel by mutableStateOf(""); private set
    var models = mutableStateListOf<ModelInfo>(); private set
    var thinkingLevels = mutableStateListOf<String>(); private set
    var isStreaming by mutableStateOf(false); private set
    var isLoadingHistory by mutableStateOf(false); private set
    var steeringQueue = mutableStateListOf<String>(); private set
    var followUpQueue = mutableStateListOf<String>(); private set
    var dialog by mutableStateOf<UiDialogRequest?>(null); private set

    val items = mutableStateListOf<TimelineItem>()

    /** True while a connection attempt is in flight or established. */
    val active: Boolean get() = conn == ConnState.Ready || conn == ConnState.Connecting

    private val client = PiClient(scope)
    private var keySeq = 1L
    private val pendingUserKeys = mutableSetOf<Long>()
    private var lastAction = "attach"
    private var lastSessionId: String? = null
    private var lastWorkDir = ""
    private var autoNamed = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var reconnectEnabled = false
    private var connectionGeneration = 0L

    // ---------- connection ----------

    fun connect(action: String, sessionId: String?, workDir: String = "") {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        reconnectEnabled = true
        lastAction = action
        lastSessionId = sessionId
        lastWorkDir = workDir
        startConnection(clearTimeline = true)
    }

    private fun startConnection(clearTimeline: Boolean) {
        val generation = ++connectionGeneration
        println("[PiChat] connect action=$lastAction sid=$lastSessionId workDir=$lastWorkDir")
        conn = ConnState.Connecting
        if (clearTimeline) {
            items.clear()
            pendingUserKeys.clear()
        }
        isLoadingHistory = items.isEmpty()
        client.connect(
            buildWsUrl(gateway, token, lastAction, lastSessionId, lastWorkDir),
            object : PiClient.Listener {
                override fun onOpen() {}
                override fun onMessage(text: String) {
                    if (generation == connectionGeneration) onGatewayMessage(text)
                }
                override fun onClose(code: Short, reason: String) {
                    if (generation == connectionGeneration) handleConnectionClosed(code, reason)
                }
                override fun onFailure(message: String) {
                    if (generation == connectionGeneration) handleConnectionFailure(message)
                }
            },
        )
    }

    fun reconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        reconnectEnabled = true
        if (sessionId.isNotBlank()) {
            lastAction = "attach"
            lastSessionId = sessionId
            lastWorkDir = ""
        }
        startConnection(clearTimeline = false)
    }

    fun disconnect() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        connectionGeneration++
        client.disconnect()
        conn = ConnState.Disconnected
        isStreaming = false
        isLoadingHistory = false
    }

    private fun handleConnectionClosed(code: Short, reason: String) {
        isStreaming = false
        val retryable = when (code.toInt()) {
            1000, 1002, 1003, 1007, 1008, 1009 -> false
            else -> true
        }
        if (!reconnectEnabled || !retryable || !hasSafeReconnectTarget()) {
            conn = ConnState.Disconnected
            isLoadingHistory = false
            reportConnectionClosed(code)
            return
        }
        if (reconnectAttempt == 0) reportConnectionClosed(code)
        println("[PiChat] connection closed code=$code reason=$reason; scheduling reconnect")
        scheduleReconnect()
    }

    private fun handleConnectionFailure(message: String) {
        isStreaming = false
        if (!reconnectEnabled || !hasSafeReconnectTarget()) {
            conn = ConnState.Error
            isLoadingHistory = false
            if (reconnectEnabled) onToast(message, Toast.Kind.Error)
            return
        }
        if (reconnectAttempt == 0) onToast(message, Toast.Kind.Error)
        println("[PiChat] connection failure: $message; scheduling reconnect")
        scheduleReconnect()
    }

    /** Retrying an unacknowledged create could allocate duplicate server sessions. */
    private fun hasSafeReconnectTarget(): Boolean =
        lastAction == "attach" && !lastSessionId.isNullOrBlank()

    private fun reportConnectionClosed(code: Short) {
        val s = strings()
        when (code.toInt()) {
            1011 -> status(s.piCrashed, TimelineItem.StatusItem.Tone.Error)
            1000 -> status(s.connectionClosed)
            else -> status("${s.connectionClosed} ($code)", TimelineItem.StatusItem.Tone.Warn)
        }
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled || reconnectJob != null) return
        reconnectAttempt++
        val delayMillis = reconnectDelayMillis(reconnectAttempt)
        conn = ConnState.Connecting
        println("[PiChat] reconnect attempt=$reconnectAttempt delayMs=$delayMillis")
        reconnectJob = scope.launch {
            delay(delayMillis)
            reconnectJob = null
            if (reconnectEnabled) startConnection(clearTimeline = false)
        }
    }

    fun sendCommand(build: JsonObjectBuilder.() -> Unit = {}): Boolean {
        val cmd = buildJsonObject(build)
        return client.send(cmd.toString())
    }

    // ---------- public actions ----------

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        addUserMessage(trimmed)
        maybeAutoName(trimmed)
        sendCommand {
            put("type", "prompt")
            put("message", trimmed)
            if (isStreaming) put("streamingBehavior", "steer")
        }
    }

    /** Names an unnamed session after the first user message (first line, 30 chars). */
    private fun maybeAutoName(text: String) {
        if (autoNamed || sessionName.isNotBlank() || sessionId.isBlank()) return
        autoNamed = true
        val first = text.lineSequence().first().trim()
        if (first.isEmpty()) return
        val title = if (first.length > 30) first.take(30) + "…" else first
        sessionName = title
        sendCommand { put("type", "set_session_name"); put("name", title) }
        onAutoName(sessionId, title)
    }

    fun abort() {
        sendCommand { put("type", "abort") }
        status(strings().abortSent, TimelineItem.StatusItem.Tone.Warn)
    }

    fun setModel(info: ModelInfo) {
        if (info.provider.isBlank() || info.id.isBlank()) return
        currentModel = info
        model = info.qualified
        sendCommand {
            put("type", "set_model")
            put("provider", info.provider)
            put("modelId", info.id)
        }
    }

    fun selectThinkingLevel(level: String) {
        thinkingLevel = level
        sendCommand {
            put("type", "set_thinking_level")
            put("level", level)
        }
    }

    fun renameSession(name: String) {
        sessionName = name
        sendCommand { put("type", "set_session_name"); put("name", name) }
    }

    fun respondDialog(response: JsonObjectBuilder.() -> Unit) {
        val req = dialog ?: return
        sendCommand {
            put("type", "extension_ui_response")
            put("id", req.id)
            response()
        }
        dialog = null
    }

    private fun onGatewayMessage(text: String) {
        val msg = parseMessage(text) ?: return
        try {
            dispatchMessage(msg)
        } catch (t: Throwable) {
            println("[PiChat] dispatch error: ${t.stackTraceToString().take(800)}")
        }
    }

    private fun dispatchMessage(msg: JsonObject) {
        when (msg.str("type")) {
            "pi2ws" -> handleGatewayEvent(msg)
            "response" -> handleResponse(msg)
            "message_start" -> msg.obj("message")?.let { m ->
                when (m.str("role")) {
                    "user" -> handleUserMessageStart(m)
                    "assistant" -> ensureAssistant(m)
                }
            }
            "message_update" -> msg.obj("assistantMessageEvent")?.let { handleDelta(it) }
            "message_end" -> msg.obj("message")?.let { finalizeAssistant(it) }
            "tool_execution_start" -> handleToolStart(msg)
            "tool_execution_update" -> handleToolUpdate(msg)
            "tool_execution_end" -> handleToolEnd(msg)
            "agent_start" -> isStreaming = true
            "agent_settled" -> {
                isStreaming = false
                for (item in items) {
                    if (item is TimelineItem.AssistantItem) {
                        for (blk in item.blocks) {
                            if (blk.kind == BlockKind.ToolCall && blk.tool?.state == ToolState.Pending) {
                                blk.tool?.state = ToolState.Done
                                blk.tool?.endedAt = nowMillis()
                            }
                        }
                    }
                }
            }
            "turn_start" -> Unit
            "turn_end" -> msg.obj("message")?.let { finalizeAssistant(it) }
            "queue_update" -> {
                steeringQueue.clear(); msg.arr("steering")?.mapNotNull { it.toString().trim('"').ifBlank { null } }?.let { steeringQueue.addAll(it) }
                followUpQueue.clear(); msg.arr("followUp")?.mapNotNull { it.toString().trim('"').ifBlank { null } }?.let { followUpQueue.addAll(it) }
            }
            "compaction_start" -> status(strings().compacting, TimelineItem.StatusItem.Tone.Warn)
            "compaction_end" -> status(strings().compacted)
            "auto_retry_start" -> status(
                "${strings().turnStart}: ${msg.strOrEmpty("errorMessage")}",
                TimelineItem.StatusItem.Tone.Warn,
            )
            "auto_retry_end" -> status(
                if (msg.bool("success") == true) strings().retryOk else strings().retryFailed,
            )
            "bash_execution_update" -> {
                val id = msg.str("id")
                val delta = msg.strOrEmpty("delta")
                val last = items.lastOrNull()
                if (last is TimelineItem.StatusItem && last.bashId != null && last.bashId == id) {
                    last.text += delta
                } else {
                    items.add(TimelineItem.StatusItem(keySeq++, delta, ts = nowMillis(), bashId = id))
                }
            }
            "extension_ui_request" -> handleUiRequest(msg)
            "extension_error" -> status(
                msg.strOrEmpty("error"),
                TimelineItem.StatusItem.Tone.Error,
            )
        }
    }

    private fun handleGatewayEvent(msg: JsonObject) {
        when (msg.str("event")) {
            "ready" -> {
                conn = ConnState.Ready
                val sid = msg.strOrEmpty("session_id")
                sessionId = sid
                workDir = msg.strOrEmpty("work_dir")
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                if (sid.isNotBlank()) {
                    lastAction = "attach"
                    lastSessionId = sid
                    lastWorkDir = ""
                }
                println("[PiChat] ready sid=$sid action=${msg.str("action")} workDir=$workDir")
                onSessionReady(sid, msg.str("action") == "create", workDir)
                status(
                    if (msg.str("action") == "create") strings().newSessionCreated
                    else strings().sessionAttached,
                )
                sendCommand { put("type", "get_state"); this }
                sendCommand { put("type", "get_entries"); this }
                sendCommand { put("type", "get_available_models"); this }
                sendCommand { put("type", "get_available_thinking_levels"); this }
            }
            "error" -> onToast(
                "${msg.strOrEmpty("code")}: ${msg.strOrEmpty("message")}",
                Toast.Kind.Error,
            )
        }
    }

    // ---------- responses ----------

    private fun handleResponse(msg: JsonObject) {
        val command = msg.strOrEmpty("command")
        val success = msg.bool("success") == true
        val data = msg.obj("data")
        if (!success) {
            val error = msg.strOrEmpty("error")
            if (command == "get_entries") isLoadingHistory = false
            if (command != "abort") onToast("$command: $error", Toast.Kind.Error)
            if (command == "set_model" || command == "set_thinking_level") {
                sendCommand { put("type", "get_state"); this }
            }
            return
        }
        when (command) {
            "get_state" -> if (data != null) {
                sessionName = data.strOrEmpty("sessionName")
                val modelObj = data.obj("model")
                model = modelObj?.let { m ->
                    val id = m.strOrEmpty("id"); val p = m.strOrEmpty("provider")
                    if (id.isNotEmpty() && p.isNotEmpty()) "$p/$id" else id.ifEmpty { p }
                } ?: ""
                currentModel = modelObj?.let { m ->
                    ModelInfo(m.strOrEmpty("id"), m.strOrEmpty("name"), m.strOrEmpty("provider"))
                }?.takeIf { it.id.isNotBlank() }
                thinkingLevel = data.strOrEmpty("thinkingLevel")
                isStreaming = data.bool("isStreaming") == true
            }
            "get_available_models" -> {
                models.clear()
                data?.arr("models")?.forEach { el ->
                    (el as? JsonObject)?.let { m ->
                        val id = m.strOrEmpty("id")
                        if (id.isNotBlank()) {
                            models.add(ModelInfo(id, m.strOrEmpty("name"), m.strOrEmpty("provider")))
                        }
                    }
                }
                if (currentModel == null && models.isNotEmpty()) {
                    currentModel = models.firstOrNull { model.endsWith(it.id) }
                }
            }
            "get_available_thinking_levels" -> {
                thinkingLevels.clear()
                data?.arr("levels")?.forEach { el ->
                    el.toString().trim('"').takeIf { it.isNotBlank() }?.let { thinkingLevels.add(it) }
                }
            }
            "set_model" -> {
                val m = (data?.obj("model") ?: data)
                if (m != null) {
                    val info = ModelInfo(m.strOrEmpty("id"), m.strOrEmpty("name"), m.strOrEmpty("provider"))
                    if (info.id.isNotBlank()) {
                        currentModel = info
                        model = info.qualified
                    }
                }
                sendCommand { put("type", "get_available_thinking_levels"); this }
                sendCommand { put("type", "get_state"); this }
            }
            "set_thinking_level" -> Unit // optimistic update already applied
            "get_entries" -> {
                isLoadingHistory = false
                data?.arr("entries")?.let {
                    println("[PiChat] get_entries count=${it.size}")
                    rebuildFromEntries(it)
                    println("[PiChat] rebuild done items=${items.size}")
                }
            }
            "get_messages" -> data?.arr("messages")?.let { msgs ->
                rebuildFromEntries(JsonArray(msgs.map { m ->
                    buildJsonObject { put("type", "message"); put("message", m) }
                }))
            }
            "set_session_name" -> sessionName = data?.strOrEmpty("name") ?: sessionName
        }
    }

    // ---------- timeline: streaming ----------

    private fun status(text: String, tone: TimelineItem.StatusItem.Tone = TimelineItem.StatusItem.Tone.Info) {
        if (text.isBlank()) return
        items.add(TimelineItem.StatusItem(keySeq++, text, tone, nowMillis()))
    }

    private fun latestStreamingAssistant(): TimelineItem.AssistantItem? =
        items.asReversed().firstOrNull { it is TimelineItem.AssistantItem && it.streaming }
            as? TimelineItem.AssistantItem

    private fun ensureAssistant(message: JsonObject): TimelineItem.AssistantItem {
        latestStreamingAssistant()?.let { if (it.blocks.isEmpty()) return it }
        val item = TimelineItem.AssistantItem(
            key = keySeq++,
            model = message.str("model"),
            stopReason = message.str("stopReason"),
            streaming = true,
        )
        items.add(item)
        return item
    }

    private fun blockAt(item: TimelineItem.AssistantItem, index: Int, kind: BlockKind): AssistantBlock {
        while (item.blocks.size <= index) {
            item.blocks.add(AssistantBlock(if (item.blocks.size == index) kind else BlockKind.Text))
        }
        val b = item.blocks[index]
        if (b.kind != kind) {
            b.kind = kind
            if (kind == BlockKind.ToolCall) b.tool = ToolCallView(startedAt = nowMillis())
        }
        return b
    }

    private fun handleDelta(delta: JsonObject) {
        val item = latestStreamingAssistant() ?: return
        val index = delta.long("contentIndex")?.toInt() ?: 0
        when (delta.str("type")) {
            "start" -> delta.obj("partial")?.arr("content")?.forEachIndexed { i, el ->
                (el as? JsonObject)?.let { b ->
                    when (b.str("type")) {
                        "thinking" -> b.str("thinking")?.let { blockAt(item, i, BlockKind.Thinking).text += it }
                        "text" -> b.str("text")?.let { blockAt(item, i, BlockKind.Text).text += it }
                    }
                }
            }
            "text_start" -> blockAt(item, index, BlockKind.Text)
            "text_delta" -> blockAt(item, index, BlockKind.Text).text += delta.strOrEmpty("delta")
            "text_end" -> blockAt(item, index, BlockKind.Text).text = delta.strOrEmpty("content")
            "thinking_start" -> blockAt(item, index, BlockKind.Thinking)
            "thinking_delta" -> blockAt(item, index, BlockKind.Thinking).text += delta.strOrEmpty("delta")
            "thinking_end" -> blockAt(item, index, BlockKind.Thinking).text = delta.strOrEmpty("content")
            "toolcall_start" -> blockAt(item, index, BlockKind.ToolCall)
            "toolcall_delta" -> blockAt(item, index, BlockKind.ToolCall).tool?.let { it.args += delta.strOrEmpty("delta") }
            "toolcall_end" -> blockAt(item, index, BlockKind.ToolCall).tool?.let { t ->
                val call = delta.obj("toolCall")
                t.id = call?.strOrEmpty("id").orEmpty().ifBlank { t.id }
                t.name = call?.strOrEmpty("name").orEmpty().ifBlank { t.name }
                t.args = argsToString(call?.get("arguments")).ifBlank { t.args }
                t.argsDone = true
                reconcileStandaloneTool(items, t)
            }
            "done", "error" -> item.stopReason = delta.str("reason")
        }
    }

    private fun finalizeAssistant(message: JsonObject) {
        val item = latestStreamingAssistant() ?: return
        (message["content"] as? JsonArray)?.forEachIndexed { i, el ->
            (el as? JsonObject)?.let { b ->
                when (b.str("type")) {
                    "thinking" -> blockAt(item, i, BlockKind.Thinking).text = b.strOrEmpty("thinking")
                    "text" -> blockAt(item, i, BlockKind.Text).text = b.strOrEmpty("text")
                    "toolCall" -> {
                        val blk = blockAt(item, i, BlockKind.ToolCall)
                        blk.tool?.let { t ->
                            t.id = b.strOrEmpty("id").ifBlank { t.id }
                            t.name = b.strOrEmpty("name").ifBlank { t.name }
                            t.args = argsToString(b["arguments"]).ifBlank { t.args }
                            t.argsDone = true
                            reconcileStandaloneTool(items, t)
                            if (t.state == ToolState.Streaming) t.state = ToolState.Pending
                        }
                    }
                }
            }
        }
        for (blk in item.blocks) {
            if (blk.kind == BlockKind.ToolCall && blk.tool?.state == ToolState.Streaming) {
                blk.tool?.state = ToolState.Done
                blk.tool?.outputDone = true
                blk.tool?.endedAt = nowMillis()
            }
        }
        item.model = message.str("model") ?: item.model
        item.stopReason = message.str("stopReason") ?: item.stopReason
        item.streaming = false
        item.ts = message.long("timestamp") ?: nowMillis()
    }

    private fun addUserMessage(text: String) {
        val item = TimelineItem.UserItem(keySeq++, text, nowMillis())
        pendingUserKeys.add(item.key)
        items.add(item)
    }

    private fun handleUserMessageStart(message: JsonObject) {
        val echoed = contentText(message["content"])
        for (i in items.indices.reversed()) {
            when (val it = items[i]) {
                is TimelineItem.UserItem -> {
                    if (pendingUserKeys.contains(it.key)) {
                        pendingUserKeys.remove(it.key)
                        if (echoed.isNotEmpty()) it.text = echoed
                        it.ts = message.long("timestamp") ?: nowMillis()
                    }
                    return
                }
                is TimelineItem.StatusItem -> Unit
                else -> return
            }
        }
        items.add(TimelineItem.UserItem(keySeq++, echoed, message.long("timestamp") ?: nowMillis()))
    }

    // ---------- timeline: tools ----------

    private fun findTool(toolCallId: String): ToolCallView? {
        if (toolCallId.isBlank()) return null
        for (i in items.indices.reversed()) {
            when (val it = items[i]) {
                is TimelineItem.AssistantItem -> {
                    for (b in it.blocks) {
                        if (b.kind == BlockKind.ToolCall && b.tool?.id == toolCallId) return b.tool
                    }
                }
                is TimelineItem.ToolItem -> if (it.tool.id == toolCallId) return it.tool
                else -> Unit
            }
        }
        return null
    }

    private fun findUnboundAssistantTool(toolName: String): ToolCallView? {
        val assistant = latestStreamingAssistant() ?: return null
        return assistant.blocks.mapNotNull { block ->
            block.tool?.takeIf { tool ->
                block.kind == BlockKind.ToolCall &&
                    tool.id.isBlank() &&
                    (tool.name.isBlank() || toolName.isBlank() || tool.name == toolName) &&
                    (tool.state == ToolState.Streaming || tool.state == ToolState.Pending)
            }
        }.singleOrNull()
    }

    private fun handleToolStart(msg: JsonObject) {
        val callId = msg.strOrEmpty("toolCallId")
        val toolName = msg.strOrEmpty("toolName")
        var tc = findTool(callId) ?: findUnboundAssistantTool(toolName)
        if (tc == null) {
            tc = ToolCallView(id = callId, startedAt = nowMillis())
            items.add(TimelineItem.ToolItem(keySeq++, tc))
        }
        if (tc.id.isBlank() && callId.isNotBlank()) tc.id = callId
        tc.name = toolName.ifBlank { tc.name }
        tc.args = argsToString(msg.obj("args")).ifBlank { tc.args }
        tc.argsDone = true
        tc.state = ToolState.Running
        tc.startedAt = nowMillis()
    }

    private fun handleToolUpdate(msg: JsonObject) {
        val tc = findTool(msg.strOrEmpty("toolCallId")) ?: return
        tc.output = plainText(msg.obj("partialResult")?.get("content"))
        tc.state = ToolState.Running
    }

    private fun handleToolEnd(msg: JsonObject) {
        val tc = findTool(msg.strOrEmpty("toolCallId")) ?: return
        tc.output = plainText(msg.obj("result")?.get("content"))
        tc.outputDone = true
        tc.isError = msg.bool("isError") == true
        tc.state = if (tc.isError) ToolState.Error else ToolState.Done
        tc.endedAt = nowMillis()
    }

    // ---------- history rebuild ----------

    private fun rebuildFromEntries(entries: JsonArray) {
        items.clear()
        pendingUserKeys.clear()
        val toolByCallId = HashMap<String, ToolCallView>()
        for (raw in entries) {
            val entry = raw as? JsonObject ?: continue
            if (entry.str("type") != "message") continue
            val msg = entry.obj("message") ?: continue
            val ts = msg.long("timestamp") ?: nowMillis()
            when (msg.str("role")) {
                "user" -> items.add(TimelineItem.UserItem(keySeq++, contentText(msg["content"]), ts))
                "assistant" -> {
                    val item = TimelineItem.AssistantItem(
                        key = keySeq++,
                        model = msg.str("model"),
                        stopReason = msg.str("stopReason"),
                        streaming = false,
                        ts = ts,
                    )
                    (msg["content"] as? JsonArray)?.forEach { el ->
                        (el as? JsonObject)?.let { b ->
                            when (b.str("type")) {
                                "thinking" -> item.blocks.add(AssistantBlock(BlockKind.Thinking, b.strOrEmpty("thinking")))
                                "text" -> item.blocks.add(AssistantBlock(BlockKind.Text, b.strOrEmpty("text")))
                                "toolCall" -> {
                                    val tc = ToolCallView(
                                        id = b.strOrEmpty("id"),
                                        name = b.strOrEmpty("name"),
                                        args = argsToString(b["arguments"]),
                                        argsDone = true,
                                        state = ToolState.Pending,
                                        startedAt = ts,
                                    )
                                    item.blocks.add(AssistantBlock(BlockKind.ToolCall, tool = tc))
                                    if (tc.id.isNotBlank()) toolByCallId[tc.id] = tc
                                }
                            }
                        }
                    }
                    items.add(item)
                }
                "toolResult" -> {
                    val tc = msg.str("toolCallId")?.let { toolByCallId[it] }
                    val output = contentText(msg["content"])
                    val isError = msg.bool("isError") == true
                    if (tc != null) {
                        tc.output = output
                        tc.outputDone = true
                        tc.isError = isError
                        tc.state = if (isError) ToolState.Error else ToolState.Done
                        tc.endedAt = ts
                    } else {
                        items.add(TimelineItem.ToolItem(keySeq++, ToolCallView(
                            id = msg.strOrEmpty("toolCallId"),
                            name = msg.strOrEmpty("toolName").ifBlank { "tool" },
                            output = output,
                            outputDone = true,
                            state = if (isError) ToolState.Error else ToolState.Done,
                            isError = isError,
                            startedAt = ts,
                            endedAt = ts,
                        )))
                    }
                }
                "bashExecution" -> items.add(TimelineItem.StatusItem(
                    keySeq++,
                    "bash: ${msg.strOrEmpty("command")} (exit ${msg.long("exitCode") ?: "?"})",
                    if ((msg.long("exitCode") ?: 0L) == 0L) TimelineItem.StatusItem.Tone.Info
                    else TimelineItem.StatusItem.Tone.Warn,
                    ts,
                ))
            }
        }
    }

    // ---------- extension UI ----------

    private fun handleUiRequest(msg: JsonObject) {
        when (msg.str("method")) {
            "select", "confirm", "input", "editor" -> dialog = UiDialogRequest(
                id = msg.strOrEmpty("id"),
                method = msg.strOrEmpty("method"),
                title = msg.strOrEmpty("title"),
                message = msg.strOrEmpty("message"),
                options = msg.arr("options")?.map { it.toString().trim('"') } ?: emptyList(),
                placeholder = msg.strOrEmpty("placeholder"),
                prefill = msg.strOrEmpty("prefill"),
            )
            "notify" -> onToast(msg.str("text") ?: msg.strOrEmpty("title"), Toast.Kind.Info)
            "setStatus", "setTitle" -> msg.str("status")?.let { status(it) }
                ?: msg.str("title")?.let { sessionName = it }
            else -> Unit
        }
    }
}

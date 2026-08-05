package io.github.yearsyan.pi.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.data.SessionSyncPhase
import io.github.yearsyan.pi.net.GatewayCapabilities
import io.github.yearsyan.pi.net.GatewayConnectionException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.random.Random

internal const val InitialReconnectDelayMillis = 1_000L
internal const val MaxReconnectDelayMillis = 30_000L
private const val RateWindowMillis = 500L
private val OrderedThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

internal data class DecodedEntryCache(
    val entries: JsonArray,
    val lastEntryId: String,
)

internal data class UserMessageContent(
    val text: String,
    val images: List<TimelineImage>,
)

internal fun userMessageContent(content: JsonElement?): UserMessageContent {
    val text = compactSkillInvocation(contentText(content))
    val images =
        (content as? JsonArray).orEmpty().mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            if (block.str("type") != "image") return@mapNotNull null
            val data = block.strOrEmpty("data")
            if (data.isEmpty()) return@mapNotNull null
            TimelineImage(
                data = data,
                mimeType = block.strOrEmpty("mimeType").ifBlank { "image/jpeg" },
            )
        }
    return UserMessageContent(text = text, images = images)
}

internal fun decodeEntryCache(bytes: ByteArray): DecodedEntryCache? {
    if (bytes.isEmpty()) return DecodedEntryCache(JsonArray(emptyList()), "")
    val byId = LinkedHashMap<String, JsonObject>()
    var lastEntryId = ""
    for (line in bytes.decodeToString().lineSequence()) {
        if (line.isBlank()) continue
        val entry = runCatching { PiJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val id = entry.strOrEmpty("id")
        if (id.isBlank()) return null
        byId[id] = entry
        lastEntryId = id
    }
    return DecodedEntryCache(JsonArray(byId.values.toList()), lastEntryId)
}

internal enum class PromptDispatch {
    ExistingSession,
    CreateSession,
    Unavailable,
}

internal fun promptDispatch(
    conn: ConnState,
    isDraft: Boolean,
    hasPendingCreatePrompt: Boolean,
): PromptDispatch =
    when {
        conn == ConnState.Ready -> PromptDispatch.ExistingSession
        isDraft && !hasPendingCreatePrompt &&
            (conn == ConnState.Disconnected || conn == ConnState.Error) -> PromptDispatch.CreateSession
        else -> PromptDispatch.Unavailable
    }

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

internal fun sessionSyncPhaseForEvent(event: String?): SessionSyncPhase? = when (event) {
    "history_begin" -> SessionSyncPhase.RestoringHistory
    "replay_begin" -> SessionSyncPhase.CatchingUp
    "ready" -> SessionSyncPhase.Idle
    else -> null
}

internal fun shouldFinalizeAssistant(eventType: String): Boolean = eventType == "message_end"

internal fun clampDraftThinkingLevel(requested: String, available: List<String>): String {
    if (available.isEmpty()) return ""
    if (requested in available) return requested
    val requestedIndex = OrderedThinkingLevels.indexOf(requested)
    if (requestedIndex < 0) return available.first()
    for (index in requestedIndex until OrderedThinkingLevels.size) {
        OrderedThinkingLevels[index].takeIf { it in available }?.let { return it }
    }
    for (index in requestedIndex - 1 downTo 0) {
        OrderedThinkingLevels[index].takeIf { it in available }?.let { return it }
    }
    return available.first()
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
    private val onSessionNameChanged: (sessionId: String, title: String) -> Unit,
    private val onStreamingChanged: (sessionId: String, streaming: Boolean) -> Unit,
    private val strings: () -> ChatStrings,
    private val loadCapabilities: suspend (workDir: String) -> GatewayCapabilities,
    private val resolveGateway: suspend () -> String = { gateway },
    private val cacheNamespace: String = gateway,
) {
    private data class PendingPrompt(
        val sourceId: String,
        val text: String,
        val images: List<PromptImage>,
        val streaming: Boolean,
        val displayText: String,
        val priorOccurrences: Int,
        val confirmOnResponse: Boolean,
    )

    /** Small string contract so the controller stays UI-independent. */
    data class ChatStrings(
        val commandRejected: String,
        val abortSent: String,
        val compacting: String,
        val compacted: String,
        val retryOk: String,
        val retryFailed: String,
        val agentDone: String,
        val turnStart: String,
        val notify: String,
        val modelOptionsFailed: (String) -> String,
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
    var slashCommands = mutableStateListOf<SlashCommand>(); private set
    var capabilitiesLoading by mutableStateOf(false); private set
    var capabilitiesError by mutableStateOf<String?>(null); private set
    var isStreaming by mutableStateOf(false); private set
    var sessionStats by mutableStateOf<SessionStats?>(null); private set
    var sessionStatsLoading by mutableStateOf(false); private set
    var charRate by mutableStateOf(0f); private set
    var runningToolCount by mutableStateOf(0); private set
    private var rateWindowStart = 0L
    private var rateWindowChars = 0
    var isLoadingHistory by mutableStateOf(false); private set
    var syncPhase by mutableStateOf(SessionSyncPhase.Idle); private set
    var steeringQueue = mutableStateListOf<String>(); private set
    var followUpQueue = mutableStateListOf<String>(); private set
    var dialog by mutableStateOf<UiDialogRequest?>(null); private set

    val items = mutableStateListOf<TimelineItem>()

    /** True while a connection attempt is in flight or established. */
    val active: Boolean get() = conn == ConnState.Ready || conn == ConnState.Connecting

    /** True until a locally prepared chat receives its server-assigned session ID. */
    val isDraft: Boolean get() = draftWorkDir != null

    /** True when a prompt can be sent now or can create this local draft. */
    val canSendPrompt: Boolean
        get() = pendingPrompt == null && pendingCompactId == null && !capabilitiesLoading &&
            promptDispatch(conn, isDraft, pendingCreatePrompt != null) != PromptDispatch.Unavailable

    /** True after submit and until the corresponding prompt or command is accepted. */
    val isPromptPending: Boolean get() = pendingPrompt != null || pendingCompactId != null

    /** Manual compaction requires an idle, attached session. */
    val canCompact: Boolean
        get() = conn == ConnState.Ready && !isStreaming && pendingPrompt == null && pendingCompactId == null

    /** True while a local draft can still change its startup model options. */
    val canConfigureDraft: Boolean
        get() = isDraft && !capabilitiesLoading && pendingCreatePrompt == null &&
            (conn == ConnState.Disconnected || conn == ConnState.Error)

    /** A draft can retry only after its first create attempt has stopped. */
    val canReconnect: Boolean
        get() = !isDraft ||
            (pendingCreatePrompt != null && (conn == ConnState.Disconnected || conn == ConnState.Error))

    private val client = PiClient(scope)
    private val entryCache = EntryCacheStore()
    private var keySeq = 1L
    private var lastAction = "attach"
    private var lastSessionId: String? = null
    private var lastWorkDir = ""
    private var reconnectJob: Job? = null
    private var connectionSetupJob: Job? = null
    private var capabilitiesJob: Job? = null
    private var capabilitiesGeneration = 0L
    private var reconnectAttempt = 0
    private var reconnectEnabled = false
    private var connectionGeneration = 0L
    private var sessionStatsRefreshQueued = false
    private var activeEntryCacheKey = ""
    private var requestedEntryCursor = ""
    private var historyTargetCursor = ""
    private var historyUpdating = false
    private var replayChunkSeq: Long? = null
    private val replayChunkParts = mutableListOf<ByteArray>()
    private var replayChunkBytes = 0
    private var draftWorkDir by mutableStateOf<String?>(null)
    private var pendingCreatePrompt by mutableStateOf<PendingPrompt?>(null)
    private var pendingPrompt by mutableStateOf<PendingPrompt?>(null)
    private var pendingCompactId by mutableStateOf<String?>(null)
    var lastConfirmedPromptSourceId by mutableStateOf<String?>(null); private set

    // ---------- connection ----------

    /** Prepares a local-only chat. Its first prompt starts the create connection. */
    fun prepareCreate(workDir: String = "") {
        disconnect()
        sessionStats = null
        sessionId = ""
        this.workDir = workDir
        draftWorkDir = workDir
        pendingCreatePrompt = null
        pendingPrompt = null
        pendingCompactId = null
        models.clear()
        thinkingLevels.clear()
        slashCommands.clear()
        model = ""
        currentModel = null
        thinkingLevel = ""
        capabilitiesError = null
        lastAction = "create"
        lastSessionId = null
        lastWorkDir = workDir
        reloadCapabilities()
    }

    /** Retries sessionless model discovery for the current local draft. */
    fun reloadCapabilities() {
        val draft = draftWorkDir ?: return
        if (capabilitiesLoading) return
        capabilitiesJob?.cancel()
        val generation = ++capabilitiesGeneration
        capabilitiesLoading = true
        capabilitiesError = null
        capabilitiesJob = scope.launch {
            try {
                val capabilities = loadCapabilities(draft)
                if (draftWorkDir != draft || generation != capabilitiesGeneration) return@launch
                applyDraftCapabilities(capabilities)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (draftWorkDir != draft || generation != capabilitiesGeneration) return@launch
                val message = failure.message ?: failure::class.simpleName ?: "unknown error"
                capabilitiesError = message
                onToast(strings().modelOptionsFailed(message), Toast.Kind.Error)
            } finally {
                if (draftWorkDir == draft && generation == capabilitiesGeneration) capabilitiesLoading = false
            }
        }
    }

    private fun applyDraftCapabilities(capabilities: GatewayCapabilities) {
        val available = capabilities.models.mapNotNull { remote ->
            remote.id.takeIf { it.isNotBlank() }?.let {
                ModelInfo(
                    id = remote.id,
                    name = remote.name,
                    provider = remote.provider,
                    thinkingLevels = remote.thinkingLevels,
                )
            }
        }
        models.clear()
        models.addAll(available)
        val defaults = capabilities.defaultSelection
        val selected = defaults?.let { selection ->
            available.firstOrNull {
                it.provider == selection.provider && it.id == selection.modelId
            }
        } ?: available.firstOrNull()
        currentModel = selected
        model = selected?.qualified.orEmpty()
        replaceThinkingLevels(selected?.thinkingLevels.orEmpty())
        replaceSlashCommands(
            capabilities.commands.mapNotNull { remote ->
                remote.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
                    SlashCommand(
                        name = name,
                        description = remote.description.trim(),
                        source = SlashCommandSource.fromWire(remote.source),
                    )
                }
            },
        )
        val defaultThinking = defaults?.thinkingLevel.orEmpty()
        thinkingLevel = clampDraftThinkingLevel(defaultThinking, thinkingLevels)
    }

    private fun replaceThinkingLevels(levels: List<String>) {
        thinkingLevels.clear()
        thinkingLevels.addAll(levels.distinct())
    }

    private fun replaceSlashCommands(commands: List<SlashCommand>) {
        slashCommands.clear()
        slashCommands.addAll(commands.distinctBy { it.name })
    }

    fun connect(action: String, sessionId: String?, workDir: String = "") {
        draftWorkDir = null
        pendingCreatePrompt = null
        pendingPrompt = null
        pendingCompactId = null
        slashCommands.clear()
        sessionStats = null
        sessionStatsLoading = false
        sessionStatsRefreshQueued = false
        beginConnection(action, sessionId, workDir, clearTimeline = true)
    }

    private fun beginConnection(
        action: String,
        sessionId: String?,
        workDir: String,
        clearTimeline: Boolean,
    ) {
        capabilitiesJob?.cancel()
        capabilitiesJob = null
        capabilitiesGeneration++
        capabilitiesLoading = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        reconnectEnabled = true
        lastAction = action
        lastSessionId = sessionId
        lastWorkDir = workDir
        startConnection(clearTimeline)
    }

    private fun connectDraft() {
        val workDir = draftWorkDir ?: return
        beginConnection("create", null, workDir, clearTimeline = false)
    }

    private fun startConnection(clearTimeline: Boolean) {
        val generation = ++connectionGeneration
        println("[PiChat] connect action=$lastAction sid=$lastSessionId workDir=$lastWorkDir")
        connectionSetupJob?.cancel()
        client.disconnect()
        conn = ConnState.Connecting
        syncPhase = SessionSyncPhase.Idle
        if (clearTimeline) {
            items.clear()
        }
        entryCache.abort()
        historyUpdating = false
        replayChunkSeq = null
        replayChunkParts.clear()
        replayChunkBytes = 0
        isLoadingHistory = lastAction == "attach"
        connectionSetupJob = scope.launch {
            var entryCursor = ""
            if (lastAction == "attach" && !lastSessionId.isNullOrBlank()) {
                val key = entryCacheKey(cacheNamespace, lastSessionId.orEmpty())
                val cached = withContext(Dispatchers.Default) {
                    runCatching {
                        if (clearTimeline) entryCache.snapshot(key)
                        else EntryCacheSnapshot(entryCache.cursor(key), ByteArray(0))
                    }.getOrElse {
                        runCatching { entryCache.clear(key) }
                        EntryCacheSnapshot("", ByteArray(0))
                    }
                }
                if (generation != connectionGeneration) return@launch
                activeEntryCacheKey = key
                entryCursor = cached.cursor
                if (clearTimeline) {
                    val decoded = withContext(Dispatchers.Default) { decodeEntryCache(cached.entries) }
                    if (decoded == null || decoded.lastEntryId != cached.cursor) {
                        withContext(Dispatchers.Default) { runCatching { entryCache.clear(key) } }
                        entryCursor = ""
                    } else if (decoded.entries.isNotEmpty()) {
                        rebuildFromEntries(decoded.entries)
                    }
                }
            } else {
                activeEntryCacheKey = ""
            }
            requestedEntryCursor = entryCursor
            val resolvedGateway =
                try {
                    resolveGateway()
                } catch (failure: GatewayConnectionException) {
                    if (generation == connectionGeneration) {
                        handleConnectionFailure(failure.message.orEmpty(), failure.retryable)
                    }
                    return@launch
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (generation == connectionGeneration) {
                        handleConnectionFailure(
                            failure.message ?: failure::class.simpleName ?: "Connection setup failed",
                            retryable = false,
                        )
                    }
                    return@launch
                }
            if (generation != connectionGeneration) return@launch
            client.connect(
                buildWsUrl(
                    base = resolvedGateway,
                    token = token,
                    action = lastAction,
                    sessionId = lastSessionId,
                    workDir = lastWorkDir,
                    initialModel = if (lastAction == "create") currentModel?.qualified.orEmpty() else "",
                    initialThinking = if (lastAction == "create") thinkingLevel else "",
                    entrySince = entryCursor,
                ),
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
    }

    fun reconnect() {
        if (!canReconnect) return
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
        connectionSetupJob?.cancel()
        connectionSetupJob = null
        capabilitiesJob?.cancel()
        capabilitiesJob = null
        capabilitiesGeneration++
        capabilitiesLoading = false
        reconnectAttempt = 0
        connectionGeneration++
        entryCache.abort()
        historyUpdating = false
        client.disconnect()
        conn = ConnState.Disconnected
        syncPhase = SessionSyncPhase.Idle
        sessionStatsLoading = false
        sessionStatsRefreshQueued = false
        isStreaming = false
        runningToolCount = 0
        charRate = 0f
        rateWindowStart = 0L
        rateWindowChars = 0
        isLoadingHistory = false
        pendingCreatePrompt = null
        pendingPrompt = null
        pendingCompactId = null
    }

    private fun handleConnectionClosed(code: Short, reason: String) {
        entryCache.abort()
        historyUpdating = false
        syncPhase = SessionSyncPhase.Idle
        isStreaming = false
        sessionStatsLoading = false
        sessionStatsRefreshQueued = false
        runningToolCount = 0
        val retryable = when (code.toInt()) {
            1000, 1002, 1003, 1007, 1008, 1009 -> false
            else -> true
        }
        if (!reconnectEnabled || !retryable || !hasSafeReconnectTarget()) {
            conn = ConnState.Disconnected
            isLoadingHistory = false
            return
        }
        println("[PiChat] connection closed code=$code reason=$reason; scheduling reconnect")
        scheduleReconnect()
    }

    private fun handleConnectionFailure(message: String, retryable: Boolean = true) {
        entryCache.abort()
        historyUpdating = false
        syncPhase = SessionSyncPhase.Idle
        isStreaming = false
        sessionStatsLoading = false
        sessionStatsRefreshQueued = false
        runningToolCount = 0
        if (!reconnectEnabled || !retryable || !hasSafeReconnectTarget()) {
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

    /** Refreshes context and token usage for the attached session. */
    fun refreshSessionStats(): Boolean {
        if (conn != ConnState.Ready) return false
        if (sessionStatsLoading) {
            sessionStatsRefreshQueued = true
            return true
        }
        sessionStatsLoading = true
        val sent = sendCommand { put("type", "get_session_stats") }
        if (!sent) sessionStatsLoading = false
        return sent
    }

    private fun finishSessionStatsRefresh(runQueuedRefresh: Boolean) {
        sessionStatsLoading = false
        val refreshAgain = runQueuedRefresh && sessionStatsRefreshQueued && conn == ConnState.Ready
        sessionStatsRefreshQueued = false
        if (refreshAgain) refreshSessionStats()
    }

    /** Whether the current composer contents can be submitted as a prompt or local RPC command. */
    fun canSubmitInput(text: String, hasImage: Boolean): Boolean {
        val invocation = parseSlashInvocation(text)
        return if (invocation?.name == "compact") {
            !hasImage && canCompact
        } else {
            canSendPrompt && (text.isNotBlank() || hasImage)
        }
    }

    /** Routes app-owned slash commands to RPC and all other input through pi's prompt handler. */
    fun submitInput(text: String, images: List<PromptImage> = emptyList()): String? {
        val invocation = parseSlashInvocation(text)
        return if (invocation?.name == "compact") {
            if (images.isNotEmpty()) null else compact(invocation.arguments)
        } else {
            sendPrompt(text, images)
        }
    }

    fun sendPrompt(text: String, images: List<PromptImage> = emptyList()): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return null
        val dispatch = promptDispatch(conn, isDraft, pendingCreatePrompt != null)
        if (pendingPrompt != null || dispatch == PromptDispatch.Unavailable) return null
        val displayText = trimmed
        val prompt = PendingPrompt(
            sourceId = nextPromptSourceId(),
            text = trimmed,
            images = images.toList(),
            streaming = isStreaming,
            displayText = displayText,
            priorOccurrences =
                items.count {
                    it is TimelineItem.UserItem &&
                        it.text == displayText &&
                        it.images.size == images.size
                },
            // Slash inputs may be expanded (skills/templates) or handled by an
            // extension without ever emitting a matching user message.
            confirmOnResponse = parseSlashInvocation(trimmed) != null,
        )
        pendingPrompt = prompt
        return when (dispatch) {
            PromptDispatch.ExistingSession -> {
                if (sendPromptNow(prompt)) prompt.sourceId else {
                    pendingPrompt = null
                    null
                }
            }
            PromptDispatch.CreateSession -> {
                pendingCreatePrompt = prompt
                connectDraft()
                prompt.sourceId
            }
            PromptDispatch.Unavailable -> null
        }
    }

    private fun sendPromptNow(prompt: PendingPrompt): Boolean =
        client.send(
            buildPromptCommand(
                sourceId = prompt.sourceId,
                text = prompt.text,
                images = prompt.images,
                isStreaming = prompt.streaming,
                confirmOnResponse = prompt.confirmOnResponse,
            ).toString(),
        )

    private fun flushPendingCreatePrompt() {
        val prompt = pendingCreatePrompt ?: return
        if (sendPromptNow(prompt)) pendingCreatePrompt = null
    }

    private fun nextPromptSourceId(): String = buildString {
        append("pi-app-")
        append(nowMillis().toString(16))
        append('-')
        repeat(12) {
            append(Random.nextInt(256).toString(16).padStart(2, '0'))
        }
    }

    private fun compact(customInstructions: String): String? {
        if (!canCompact) return null
        val requestId = nextPromptSourceId()
        pendingCompactId = requestId
        if (!client.send(buildCompactCommand(requestId, customInstructions).toString())) {
            pendingCompactId = null
            return null
        }
        return requestId
    }

    private fun applyObservedSessionName(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        sessionName = normalized
        sessionId.takeIf { it.isNotBlank() }?.let { onSessionNameChanged(it, normalized) }
    }

    fun abort() {
        sendCommand { put("type", "abort") }
        status(strings().abortSent, TimelineItem.StatusItem.Tone.Warn)
    }

    fun setModel(info: ModelInfo) {
        if (info.provider.isBlank() || info.id.isBlank()) return
        if (isDraft && !canConfigureDraft) return
        currentModel = info
        model = info.qualified
        if (isDraft) {
            val previousThinking = thinkingLevel
            replaceThinkingLevels(info.thinkingLevels)
            thinkingLevel = clampDraftThinkingLevel(previousThinking, thinkingLevels)
            return
        }
        sendCommand {
            put("type", "set_model")
            put("provider", info.provider)
            put("modelId", info.id)
        }
    }

    fun selectThinkingLevel(level: String) {
        if (level !in thinkingLevels) return
        if (isDraft && !canConfigureDraft) return
        thinkingLevel = level
        if (isDraft) return
        sendCommand {
            put("type", "set_thinking_level")
            put("level", level)
        }
    }

    fun setSessionNameLocally(name: String) {
        sessionName = name
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
            if (msg.str("type") == "pi2ws" &&
                msg.strOrEmpty("event") in setOf(
                    "history_begin", "history_chunk", "history_end",
                    "replay_begin", "replay_chunk", "replay_end",
                )
            ) {
                failAttachSync(t)
            }
        }
    }

    private fun dispatchMessage(msg: JsonObject) {
        val eventType = msg.str("type")
        when (eventType) {
            "pi2ws" -> handleGatewayEvent(msg)
            "response" -> handleResponse(msg)
            "message_start" -> msg.obj("message")?.let { m ->
                when (m.str("role")) {
                    "user" -> handleUserMessageStart(msg, m)
                    "assistant" -> ensureAssistant(m)
                }
            }
            "message_update" -> msg.obj("assistantMessageEvent")?.let { handleDelta(it) }
            "message_end", "turn_end" -> if (shouldFinalizeAssistant(eventType.orEmpty())) {
                msg.obj("message")?.let { finalizeAssistant(it) }
            }
            "tool_execution_start" -> handleToolStart(msg)
            "tool_execution_update" -> handleToolUpdate(msg)
            "tool_execution_end" -> handleToolEnd(msg)
            "agent_start" -> updateServerStreaming(true)
            "agent_settled" -> {
                updateServerStreaming(false)
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
                refreshSessionStats()
            }
            "session_info_changed" -> applyObservedSessionName(msg.strOrEmpty("name"))
            "turn_start" -> Unit
            "queue_update" -> {
                steeringQueue.clear(); msg.arr("steering")?.mapNotNull { it.toString().trim('"').ifBlank { null } }?.let { steeringQueue.addAll(it) }
                followUpQueue.clear(); msg.arr("followUp")?.mapNotNull { it.toString().trim('"').ifBlank { null } }?.let { followUpQueue.addAll(it) }
            }
            "compaction_start" -> status(strings().compacting, TimelineItem.StatusItem.Tone.Warn)
            "compaction_end" -> {
                status(strings().compacted)
                refreshSessionStats()
            }
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
        val event = msg.str("event")
        sessionSyncPhaseForEvent(event)?.let { syncPhase = it }
        when (event) {
            "history_begin" -> beginHistorySync(msg)
            "history_chunk" -> {
                check(historyUpdating) { "unexpected history chunk" }
                entryCache.append(Base64.decode(msg.strOrEmpty("data")))
            }
            "history_end" -> finishHistorySync(msg)
            "replay_begin" -> {
                replayChunkSeq = null
                replayChunkParts.clear()
                replayChunkBytes = 0
            }
            "replay_chunk" -> handleReplayChunk(msg)
            "replay_end" -> {
                check(replayChunkSeq == null) { "replay ended inside a payload" }
                isLoadingHistory = false
            }
            "ready" -> {
                val created = msg.str("action") == "create"
                conn = ConnState.Ready
                isLoadingHistory = false
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
                if (created) draftWorkDir = null
                println("[PiChat] ready sid=$sid action=${msg.str("action")} workDir=$workDir")
                onSessionReady(sid, created, workDir)
                if (created && sessionName.isNotBlank()) {
                    sendCommand { put("type", "set_session_name"); put("name", sessionName) }
                    onSessionNameChanged(sid, sessionName)
                }
                sendCommand { put("type", "get_state"); this }
                sendCommand { put("type", "get_available_models"); this }
                sendCommand { put("type", "get_available_thinking_levels"); this }
                sendCommand { put("type", "get_commands"); this }
                refreshSessionStats()
                flushPendingCreatePrompt()
            }
            "error" -> onToast(
                "${msg.strOrEmpty("code")}: ${msg.strOrEmpty("message")}",
                Toast.Kind.Error,
            )
        }
    }

    private fun beginHistorySync(msg: JsonObject) {
        check(activeEntryCacheKey.isNotBlank()) { "attach cache key is missing" }
        historyTargetCursor = msg.strOrEmpty("entry_id")
        val reset = msg.bool("reset") == true
        historyUpdating = reset || historyTargetCursor != requestedEntryCursor
        if (historyUpdating) entryCache.begin(activeEntryCacheKey, reset)
    }

    private fun finishHistorySync(msg: JsonObject) {
        val cursor = msg.strOrEmpty("entry_id")
        check(cursor == historyTargetCursor) { "history cursor changed during sync" }
        val snapshot = if (historyUpdating) {
            entryCache.commit(cursor)
        } else {
            entryCache.snapshot(activeEntryCacheKey)
        }
        historyUpdating = false
        val decoded = decodeEntryCache(snapshot.entries) ?: error("cached history is not valid JSONL")
        check(decoded.lastEntryId == cursor) { "cached history cursor does not match server cursor" }
        rebuildFromEntries(decoded.entries)
        requestedEntryCursor = cursor
    }

    private fun handleReplayChunk(msg: JsonObject) {
        val seq = msg.long("seq") ?: error("replay chunk has no sequence")
        val activeSeq = replayChunkSeq
        check(activeSeq == null || activeSeq == seq) { "replay sequence changed inside a payload" }
        replayChunkSeq = seq
        val part = Base64.decode(msg.strOrEmpty("data"))
        replayChunkParts.add(part)
        replayChunkBytes += part.size
        if (msg.bool("final") != true) return

        val payload = ByteArray(replayChunkBytes)
        var offset = 0
        for (chunk in replayChunkParts) {
            chunk.copyInto(payload, offset)
            offset += chunk.size
        }
        replayChunkSeq = null
        replayChunkParts.clear()
        replayChunkBytes = 0
        parseMessage(payload.decodeToString())?.let { dispatchMessage(it) }
            ?: error("replay payload is not a JSON object")
    }

    private fun failAttachSync(failure: Throwable) {
        entryCache.abort()
        historyUpdating = false
        activeEntryCacheKey.takeIf { it.isNotBlank() }?.let { key ->
            runCatching { entryCache.clear(key) }
        }
        reconnectEnabled = false
        client.disconnect()
        conn = ConnState.Error
        syncPhase = SessionSyncPhase.Idle
        isLoadingHistory = false
        onToast("Session synchronization failed: ${failure.message.orEmpty()}", Toast.Kind.Error)
    }

    // ---------- responses ----------

    private fun handleResponse(msg: JsonObject) {
        val command = msg.strOrEmpty("command")
        val success = msg.bool("success") == true
        val data = msg.obj("data")
        val responseID = msg.strOrEmpty("id")
        if (!success) {
            if (command == "get_session_stats") finishSessionStatsRefresh(runQueuedRefresh = false)
            val pending = pendingPrompt
            if (command in setOf("prompt", "steer", "follow_up") &&
                pending != null && pending.sourceId == responseID
            ) {
                if (pendingCreatePrompt?.sourceId == responseID) pendingCreatePrompt = null
                pendingPrompt = null
            }
            if (command == "compact" && pendingCompactId == responseID) pendingCompactId = null
            val error = msg.strOrEmpty("error")
            if (command !in setOf("abort", "get_commands")) {
                onToast("$command: $error", Toast.Kind.Error)
            }
            if (command == "set_model" || command == "set_thinking_level") {
                sendCommand { put("type", "get_state"); this }
            }
            return
        }
        val pending = pendingPrompt
        if (command == "prompt" && pending?.confirmOnResponse == true && pending.sourceId == responseID) {
            confirmPendingPrompt(responseID)
        }
        if (command == "compact" && pendingCompactId == responseID) {
            pendingCompactId = null
            lastConfirmedPromptSourceId = responseID
        }
        when (command) {
            "get_session_stats" -> {
                sessionStats = data?.let(::parseSessionStats)
                finishSessionStatsRefresh(runQueuedRefresh = true)
            }
            "get_state" -> if (data != null) {
                applyObservedSessionName(data.strOrEmpty("sessionName"))
                val modelObj = data.obj("model")
                model = modelObj?.let { m ->
                    val id = m.strOrEmpty("id"); val p = m.strOrEmpty("provider")
                    if (id.isNotEmpty() && p.isNotEmpty()) "$p/$id" else id.ifEmpty { p }
                } ?: ""
                currentModel = modelObj?.let { m ->
                    ModelInfo(m.strOrEmpty("id"), m.strOrEmpty("name"), m.strOrEmpty("provider"))
                }?.takeIf { it.id.isNotBlank() }
                thinkingLevel = data.strOrEmpty("thinkingLevel")
                updateServerStreaming(data.bool("isStreaming") == true)
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
            "get_commands" -> {
                replaceSlashCommands(
                    data?.arr("commands").orEmpty().mapNotNull { element ->
                        (element as? JsonObject)?.let { remote ->
                            remote.strOrEmpty("name").trim().takeIf { it.isNotEmpty() }?.let { name ->
                                SlashCommand(
                                    name = name,
                                    description = remote.strOrEmpty("description").trim(),
                                    source = SlashCommandSource.fromWire(remote.strOrEmpty("source")),
                                )
                            }
                        }
                    },
                )
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
            "get_messages" -> data?.arr("messages")?.let { msgs ->
                rebuildFromEntries(JsonArray(msgs.map { m ->
                    buildJsonObject { put("type", "message"); put("message", m) }
                }))
            }
            "set_session_name" -> sessionName = data?.strOrEmpty("name") ?: sessionName
        }
    }

    private fun updateServerStreaming(streaming: Boolean) {
        isStreaming = streaming
        if (streaming) {
            charRate = 0f
            rateWindowStart = 0L
            rateWindowChars = 0
        } else {
            runningToolCount = 0
        }
        sessionId.takeIf { it.isNotBlank() }?.let { onStreamingChanged(it, streaming) }
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

    /** Output character rate (chars/s), settled once per 500 ms window. */
    private fun recordOutputChars(n: Int) {
        if (n <= 0) return
        val now = nowMillis()
        if (rateWindowStart == 0L) rateWindowStart = now
        rateWindowChars += n
        if (now - rateWindowStart >= RateWindowMillis) {
            charRate = rateWindowChars * 1000f / (now - rateWindowStart)
            rateWindowStart = now
            rateWindowChars = 0
        }
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
            "text_delta" -> {
                val chunk = delta.strOrEmpty("delta")
                blockAt(item, index, BlockKind.Text).text += chunk
                recordOutputChars(chunk.length)
            }
            "text_end" -> blockAt(item, index, BlockKind.Text).text = delta.strOrEmpty("content")
            "thinking_start" -> blockAt(item, index, BlockKind.Thinking)
            "thinking_delta" -> {
                val chunk = delta.strOrEmpty("delta")
                blockAt(item, index, BlockKind.Thinking).text += chunk
                recordOutputChars(chunk.length)
            }
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
        if (message.str("role") != "assistant") return
        val item = latestStreamingAssistant() ?: ensureAssistant(message)
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

    private fun handleUserMessageStart(event: JsonObject, message: JsonObject) {
        val sourceId = event.strOrEmpty("source_id")
        val content = userMessageContent(message["content"])
        val timestamp = message.long("timestamp") ?: nowMillis()
        val existing = sourceId.takeIf { it.isNotBlank() }?.let { wanted ->
            items.asReversed().firstOrNull {
                it is TimelineItem.UserItem && it.sourceId == wanted
            } as? TimelineItem.UserItem
        }
        if (existing != null) {
            existing.text = content.text
            existing.images = content.images
            existing.ts = timestamp
        } else {
            items.add(
                TimelineItem.UserItem(
                    key = keySeq++,
                    text = content.text,
                    ts = timestamp,
                    sourceId = sourceId.ifBlank { null },
                    images = content.images,
                ),
            )
        }
        if (sourceId.isNotBlank()) confirmPendingPrompt(sourceId)
    }

    private fun confirmPendingPrompt(sourceId: String) {
        val pending = pendingPrompt ?: return
        if (pending.sourceId != sourceId) return
        if (pendingCreatePrompt?.sourceId == sourceId) pendingCreatePrompt = null
        pendingPrompt = null
        lastConfirmedPromptSourceId = sourceId
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
        if (tc.state != ToolState.Running) runningToolCount++
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
        if (tc.state == ToolState.Running) runningToolCount = (runningToolCount - 1).coerceAtLeast(0)
        tc.state = if (tc.isError) ToolState.Error else ToolState.Done
        tc.endedAt = nowMillis()
    }

    // ---------- history rebuild ----------

    private fun rebuildFromEntries(entries: JsonArray) {
        items.clear()
        val toolByCallId = HashMap<String, ToolCallView>()
        for (raw in entries) {
            val entry = raw as? JsonObject ?: continue
            if (entry.str("type") != "message") continue
            val msg = entry.obj("message") ?: continue
            val ts = msg.long("timestamp") ?: nowMillis()
            when (msg.str("role")) {
                "user" -> {
                    val content = userMessageContent(msg["content"])
                    items.add(
                        TimelineItem.UserItem(
                            key = keySeq++,
                            text = content.text,
                            ts = ts,
                            images = content.images,
                        ),
                    )
                }
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
        reconcilePendingPromptFromHistory()
    }

    private fun reconcilePendingPromptFromHistory() {
        val pending = pendingPrompt ?: return
        val matches = items.filterIsInstance<TimelineItem.UserItem>()
            .filter {
                it.text == pending.displayText && it.images.size == pending.images.size
            }
        if (matches.size <= pending.priorOccurrences) return
        matches.last().sourceId = pending.sourceId
        confirmPendingPrompt(pending.sourceId)
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

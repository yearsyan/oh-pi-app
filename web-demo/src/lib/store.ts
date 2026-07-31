import { reactive, readonly } from 'vue'
import { Pi2wsClient, buildWsUrl } from './ws'
import type {
  AgentMessage,
  AssistantBlock,
  AssistantDelta,
  AssistantItem,
  ConnState,
  ContentBlock,
  ExtensionUiRequest,
  ModelInfo,
  RpcResponse,
  SavedSession,
  Settings,
  TimelineItem,
  Toast,
  ToolCallView,
  ToolItem,
  UiDialogState,
  UserItem,
} from './types'

const SETTINGS_KEY = 'pi2ws_settings'
const SESSIONS_KEY = 'pi2ws_sessions'
const ACTIVE_KEY = 'pi2ws_active_session'

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (raw) {
      const s = JSON.parse(raw)
      return { gateway: String(s.gateway ?? 'ws://127.0.0.1:8080'), token: String(s.token ?? '') }
    }
  } catch {
    /* ignore */
  }
  return { gateway: '', token: '' }
}

function loadSessions(): SavedSession[] {
  try {
    const raw = localStorage.getItem(SESSIONS_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) return arr
    }
  } catch {
    /* ignore */
  }
  return []
}

function save<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* ignore */
  }
}

// 会话 ID 以原始字符串存储（不用 JSON.stringify，避免写入带引号的值）
function loadActiveSessionId(): string {
  const raw = localStorage.getItem(ACTIVE_KEY)
  if (!raw) return ''
  // 兼容旧版本：之前可能被 JSON.stringify 存成了带引号的字符串
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'string') return parsed
  } catch {
    /* 不是 JSON，按原样使用 */
  }
  return raw
}

function storeActiveSessionId(id: string): void {
  try {
    localStorage.setItem(ACTIVE_KEY, id)
  } catch {
    /* ignore */
  }
}

// ---------- reactive state ----------

const state = reactive({
  settings: loadSettings(),
  sessions: loadSessions() as SavedSession[],
  activeSessionId: loadActiveSessionId(),
  conn: 'disconnected' as ConnState,
  connDetail: '',
  sessionName: '',
  model: '',
  thinkingLevel: '',
  models: [] as ModelInfo[],
  thinkingLevels: [] as string[],
  currentModel: null as ModelInfo | null,
  isStreaming: false,
  items: [] as TimelineItem[],
  steeringQueue: [] as string[],
  followUpQueue: [] as string[],
  dialog: null as UiDialogState | null,
  toasts: [] as Toast[],
  settingsOpen: false,
})

let keySeq = 1
const client = new Pi2wsClient()
let pendingUserKeys = new Set<number>()

// ---------- timeline helpers ----------

function pushItem(item: TimelineItem): void {
  state.items.push(item)
  scrollToBottom()
}

function latestStreamingAssistant(): AssistantItem | undefined {
  for (let i = state.items.length - 1; i >= 0; i--) {
    const it = state.items[i]
    if (it.kind === 'assistant' && it.streaming) return it
  }
  return undefined
}

function findTool(toolCallId: string): ToolCallView | undefined {
  for (let i = state.items.length - 1; i >= 0; i--) {
    const it = state.items[i]
    if (it.kind === 'assistant') {
      const t = it.blocks.find((b) => b.kind === 'toolcall' && b.tool && b.tool.id === toolCallId)
      if (t && t.tool) return t.tool
    } else if (it.kind === 'tool' && it.tool.id === toolCallId) {
      return it.tool
    }
  }
  return undefined
}

function newToolCall(): ToolCallView {
  return {
    id: '',
    name: '',
    args: '',
    argsDone: false,
    output: '',
    outputDone: false,
    state: 'streaming',
    isError: false,
    startedAt: Date.now(),
  }
}

function status(text: string, tone: 'info' | 'warn' | 'error' = 'info'): void {
  pushItem({ kind: 'status', key: keySeq++, text, tone, ts: Date.now() })
}

function toast(text: string, kind: Toast['kind'] = 'info'): void {
  const id = Date.now() + Math.random()
  state.toasts.push({ id, text, kind })
  setTimeout(() => {
    const i = state.toasts.findIndex((t) => t.id === id)
    if (i >= 0) state.toasts.splice(i, 1)
  }, 5000)
}

// ---------- scrolling ----------

let scrollEl: HTMLElement | null = null
let scrollPinned = true

function onScroll(): void {
  if (!scrollEl) return
  scrollPinned = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < 80
}

export function registerScroller(el: HTMLElement | null): void {
  if (scrollEl) {
    scrollEl.removeEventListener('scroll', onScroll)
  }
  scrollEl = el
  scrollPinned = true
  if (el) {
    el.addEventListener('scroll', onScroll)
  }
}

function scrollToBottom(): void {
  if (scrollEl && scrollPinned) {
    scrollEl.scrollTop = scrollEl.scrollHeight
  }
}

// ---------- message / delta handling ----------

function ensureAssistant(message: AgentMessage): AssistantItem {
  const existing = latestStreamingAssistant()
  if (existing && !existing.blocks.length) {
    return existing
  }
  const item: AssistantItem = {
    kind: 'assistant',
    key: keySeq++,
    blocks: [],
    model: message.model,
    stopReason: message.stopReason,
    streaming: true,
    ts: 0,
  }
  pushItem(item)
  return item
}

function blockAt(item: AssistantItem, contentIndex: number, kind: 'thinking' | 'text' | 'toolcall'): AssistantBlock {
  let b = item.blocks[contentIndex]
  if (!b) {
    // fill any gaps (defensive)
    for (let i = item.blocks.length; i <= contentIndex; i++) {
      const nb: AssistantBlock = i === contentIndex ? { kind } : { kind: 'text', text: '' }
      item.blocks.push(nb)
    }
    b = item.blocks[contentIndex]
  }
  if (b.kind !== kind) {
    b.kind = kind
    if (kind === 'toolcall') b.tool = newToolCall()
  }
  return b
}

function handleDelta(delta: AssistantDelta): void {
  const item = latestStreamingAssistant()
  if (!item) return

  switch (delta.type) {
    case 'start': {
      // seed from the initial partial message, if the provider sent one
      const blocks = Array.isArray(delta.partial?.content) ? (delta.partial.content as ContentBlock[]) : []
      blocks.forEach((b, i) => {
        if (b.type === 'thinking' && b.thinking) {
          const blk = blockAt(item, i, 'thinking')
          blk.text = (blk.text ?? '') + b.thinking
        } else if (b.type === 'text' && b.text) {
          const blk = blockAt(item, i, 'text')
          blk.text = (blk.text ?? '') + b.text
        }
      })
      break
    }
    case 'text_start':
      blockAt(item, delta.contentIndex, 'text')
      break
    case 'text_delta': {
      const b = blockAt(item, delta.contentIndex, 'text')
      b.text = (b.text ?? '') + delta.delta
      break
    }
    case 'text_end':
      blockAt(item, delta.contentIndex, 'text').text = delta.content
      break
    case 'thinking_start':
      blockAt(item, delta.contentIndex, 'thinking')
      break
    case 'thinking_delta': {
      const b = blockAt(item, delta.contentIndex, 'thinking')
      b.text = (b.text ?? '') + delta.delta
      break
    }
    case 'thinking_end':
      blockAt(item, delta.contentIndex, 'thinking').text = delta.content
      break
    case 'toolcall_start':
      blockAt(item, delta.contentIndex, 'toolcall')
      break
    case 'toolcall_delta': {
      const b = blockAt(item, delta.contentIndex, 'toolcall')
      if (b.tool) b.tool.args += delta.delta
      break
    }
    case 'toolcall_end': {
      const b = blockAt(item, delta.contentIndex, 'toolcall')
      if (b.tool && delta.toolCall) {
        b.tool.id = delta.toolCall.id
        b.tool.name = delta.toolCall.name
        b.tool.args = stringifyArgs(delta.toolCall.arguments)
        b.tool.argsDone = true
      }
      break
    }
    case 'done':
      item.stopReason = delta.reason
      break
    case 'error':
      item.stopReason = delta.reason
      break
    default:
      break
  }
  scrollToBottom()
}

function stringifyArgs(args: unknown): string {
  if (typeof args === 'string') return args
  try {
    return JSON.stringify(args, null, 2)
  } catch {
    return String(args)
  }
}

function finalizeAssistant(message: AgentMessage): void {
  const item = latestStreamingAssistant()
  if (!item) return
  const blocks = Array.isArray(message.content) ? message.content : []
  blocks.forEach((b, i) => {
    if (b.type === 'thinking' || b.type === 'text') {
      blockAt(item, i, b.type).text = b.type === 'thinking' ? (b.thinking ?? '') : (b.text ?? '')
    } else if (b.type === 'toolCall') {
      const blk = blockAt(item, i, 'toolcall')
      if (blk.tool) {
        blk.tool.id = b.id ?? blk.tool.id
        blk.tool.name = b.name ?? blk.tool.name
        blk.tool.args = stringifyArgs(b.arguments ?? blk.tool.args)
        blk.tool.argsDone = true
        // args finished; execution may still start later (tool_execution_start
        // can arrive after message_end), so mark as pending, not done
        if (blk.tool.state === 'streaming') blk.tool.state = 'pending'
      }
    }
  })
  // any toolcalls not present in the final message were cancelled
  for (const blk of item.blocks) {
    if (blk.kind === 'toolcall' && blk.tool && blk.tool.state === 'streaming') {
      blk.tool.state = 'done'
      blk.tool.outputDone = true
      blk.tool.endedAt = Date.now()
    }
  }
  item.model = message.model
  item.stopReason = message.stopReason ?? item.stopReason
  item.streaming = false
  item.ts = message.timestamp ?? Date.now()
  scrollToBottom()
}

function addUserMessage(text: string): void {
  const item: UserItem = { kind: 'user', key: keySeq++, text, ts: Date.now() }
  pendingUserKeys.add(item.key)
  pushItem(item)
}

// pi echoes the user message back via message_start/message_end; merge it with
// our optimistic item instead of rendering a duplicate.
function handleUserMessageStart(message: AgentMessage): void {
  const echoed = extractText(message.content)
  for (let i = state.items.length - 1; i >= 0; i--) {
    const it = state.items[i]
    if (it.kind === 'user') {
      if (pendingUserKeys.has(it.key)) {
        pendingUserKeys.delete(it.key)
        if (echoed) it.text = echoed
        it.ts = message.timestamp ?? Date.now()
      }
      return
    }
    if (it.kind !== 'status') return
  }
  // from another client on the shared session
  pushItem({ kind: 'user', key: keySeq++, text: echoed, ts: message.timestamp ?? Date.now() })
}

// ---------- tool execution ----------

function extractText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((b) => (b && typeof b === 'object' ? b.text ?? '' : String(b)))
      .filter(Boolean)
      .join('\n')
  }
  return ''
}

function handleToolStart(ev: { toolCallId: string; toolName: string; args: Record<string, unknown> }): void {
  let tc = findTool(ev.toolCallId)
  if (!tc) {
    // execution started before we saw the toolcall (shouldn't happen, but be safe)
    tc = newToolCall()
    tc.id = ev.toolCallId
    tc.name = ev.toolName
    tc.argsDone = true
    const item: ToolItem = { kind: 'tool', key: keySeq++, tool: tc }
    state.items.push(item)
  }
  tc.name = ev.toolName
  tc.args = stringifyArgs(ev.args)
  tc.argsDone = true
  tc.state = 'running'
  tc.startedAt = Date.now()
  scrollToBottom()
}

function handleToolUpdate(ev: { toolCallId: string; partialResult?: { content?: unknown } }): void {
  const tc = findTool(ev.toolCallId)
  if (!tc) return
  tc.output = extractText(ev.partialResult?.content)
  tc.state = 'running'
  scrollToBottom()
}

function handleToolEnd(ev: { toolCallId: string; isError?: boolean; result?: { content?: unknown } }): void {
  const tc = findTool(ev.toolCallId)
  if (!tc) return
  tc.output = extractText(ev.result?.content)
  tc.outputDone = true
  tc.isError = ev.isError ?? false
  tc.state = tc.isError ? 'error' : 'done'
  tc.endedAt = Date.now()
  scrollToBottom()
}

// ---------- history rebuild from get_entries ----------

function rebuildFromEntries(entries: unknown[]): void {
  const items: TimelineItem[] = []
  const toolByCallId = new Map<string, ToolCallView>()

  for (const raw of entries) {
    const entry = raw as { type?: string; id?: string; message?: AgentMessage }
    if (entry.type !== 'message' || !entry.message) continue
    const msg = entry.message
    const ts = typeof msg.timestamp === 'number' ? msg.timestamp : Date.now()

    if (msg.role === 'user') {
      items.push({ kind: 'user', key: keySeq++, text: extractText(msg.content), ts })
    } else if (msg.role === 'assistant') {
      const blocks: ContentBlock[] = Array.isArray(msg.content) ? msg.content : []
      const item: AssistantItem = {
        kind: 'assistant',
        key: keySeq++,
        blocks: [],
        model: msg.model,
        stopReason: msg.stopReason,
        streaming: false,
        ts,
      }
      for (const b of blocks) {
        if (b.type === 'thinking' || b.type === 'text') {
          item.blocks.push({ kind: b.type, text: b.type === 'thinking' ? (b.thinking ?? '') : (b.text ?? '') })
        } else if (b.type === 'toolCall') {
          const tc: ToolCallView = {
            id: b.id ?? '',
            name: b.name ?? '',
            args: stringifyArgs(b.arguments),
            argsDone: true,
            output: '',
            outputDone: false,
            state: 'streaming',
            isError: false,
            startedAt: ts,
          }
          const blk: AssistantBlock = { kind: 'toolcall', tool: tc }
          item.blocks.push(blk)
          if (tc.id) toolByCallId.set(tc.id, tc)
        }
      }
      items.push(item)
    } else if (msg.role === 'toolResult') {
      const tc = msg.toolCallId ? toolByCallId.get(msg.toolCallId) : undefined
      const output = extractText(msg.content)
      if (tc) {
        tc.output = output
        tc.outputDone = true
        tc.isError = msg.isError ?? false
        tc.state = tc.isError ? 'error' : 'done'
        tc.endedAt = ts
      } else {
        const standalone: ToolCallView = {
          id: msg.toolCallId ?? '',
          name: msg.toolName ?? 'tool',
          args: '',
          argsDone: false,
          output,
          outputDone: true,
          state: msg.isError ? 'error' : 'done',
          isError: msg.isError ?? false,
          startedAt: ts,
          endedAt: ts,
        }
        items.push({ kind: 'tool', key: keySeq++, tool: standalone })
      }
    } else if (msg.role === 'bashExecution') {
      items.push({
        kind: 'status',
        key: keySeq++,
        text: `bash: ${msg.command ?? ''} (exit ${msg.exitCode ?? '?'})`,
        tone: (msg.exitCode ?? 0) === 0 ? 'info' : 'warn',
        ts,
      })
    }
  }
  state.items = items
  pendingUserKeys.clear()
  scrollToBottom()
}

// ---------- responses ----------

function handleResponse(resp: RpcResponse): void {
  if (!resp.success) {
    if (resp.command === 'prompt') {
      toast(`提示被拒绝: ${resp.error ?? 'unknown'}`, 'error')
    } else if (resp.command === 'abort') {
      // handled elsewhere
    } else {
      toast(`${resp.command}: ${resp.error ?? 'failed'}`, 'error')
    }
    // 模型/思考级别切换失败时回读状态，撤销乐观更新
    if (resp.command === 'set_model' || resp.command === 'set_thinking_level') {
      sendCommand({ type: 'get_state' })
    }
    return
  }
  const data = resp.data ?? {}
  switch (resp.command) {
    case 'get_state': {
      const d = data as Record<string, unknown>
      state.sessionName = String(d.sessionName ?? '')
      state.model = formatModel(d.model)
      state.currentModel = (d.model as ModelInfo | null) ?? null
      state.thinkingLevel = String(d.thinkingLevel ?? '')
      state.isStreaming = Boolean(d.isStreaming)
      break
    }
    case 'get_available_models':
      state.models = ((data.models as ModelInfo[]) ?? []).filter((m) => m && typeof m === 'object' && !!m.id)
      if (state.models.length && !state.currentModel) {
        const guess = state.models.find((m) => m.id === state.model.split('/').pop())
        if (guess) state.currentModel = guess
      }
      break
    case 'get_available_thinking_levels':
      state.thinkingLevels = ((data.levels as string[]) ?? []).filter(Boolean)
      break
    case 'set_model': {
      const wrapped = data as { model?: unknown }
      const model = (wrapped.model ?? data) as ModelInfo
      if (model && typeof model === 'object' && 'id' in model && model.id) {
        state.currentModel = model
        state.model = formatModel(model)
      }
      // 不同模型支持的思考强度不同，必须重新查询级别列表；
      // pi 会把旧级别钳制到新模型可用范围，get_state 同步实际值
      sendCommand({ type: 'get_available_thinking_levels' })
      sendCommand({ type: 'get_state' })
      break
    }
    case 'set_thinking_level':
      // 响应无 data，乐观更新已在发送时完成
      break
    case 'get_entries':
      rebuildFromEntries((data.entries as unknown[]) ?? [])
      break
    case 'get_messages': {
      // fallback if get_entries is unavailable
      const messages = (data.messages as AgentMessage[]) ?? []
      const entries = messages.map((m) => ({ type: 'message', message: m }))
      rebuildFromEntries(entries)
      break
    }
    case 'set_session_name':
      state.sessionName = String((data as { name?: string }).name ?? '')
      break
    default:
      break
  }
}

function formatModel(model: unknown): string {
  if (!model || typeof model !== 'object') return ''
  const m = model as Record<string, unknown>
  const id = String(m.id ?? '')
  const provider = String(m.provider ?? '')
  return id && provider ? `${provider}/${id}` : id || provider
}

// ---------- extension UI ----------

function handleUiRequest(req: ExtensionUiRequest): void {
  switch (req.method) {
    case 'select':
    case 'confirm':
    case 'input':
    case 'editor':
      state.dialog = { request: req, value: '', open: true }
      break
    case 'notify':
      toast(req.text ?? req.title ?? '通知', 'info')
      break
    case 'setTitle':
      document.title = req.title ? `${req.title} · pi2ws` : 'pi2ws Web Demo'
      break
    case 'setStatus':
      state.connDetail = req.status ?? ''
      break
    case 'setWidget':
    case 'set_editor_text':
      // fire-and-forget; nothing useful to render in the demo
      break
    default:
      break
  }
}

function respondDialog(resp: Record<string, unknown>): void {
  const dialog = state.dialog
  if (!dialog) return
  client.send({ type: 'extension_ui_response', id: dialog.request.id, ...resp })
  state.dialog = null
}

// ---------- connection ----------

function onGatewayMessage(msg: Record<string, unknown>): void {
  const type = String(msg.type)

  if (type === 'pi2ws') {
    const event = String(msg.event)
    if (event === 'ready') {
      state.conn = 'ready'
      const sid = String(msg.session_id ?? '')
      if (msg.action === 'create') {
        addSession(sid)
        state.activeSessionId = sid
        storeActiveSessionId(sid)
        status('已创建新会话', 'info')
      } else {
        state.activeSessionId = sid
        storeActiveSessionId(sid)
        status('已连接到历史会话', 'info')
      }
      sendCommand({ type: 'get_state' })
      sendCommand({ type: 'get_entries' })
      loadModelOptions()
    } else if (event === 'error') {
      toast(`网关错误 [${msg.code ?? 'error'}]: ${msg.message ?? ''}`, 'error')
    }
    return
  }

  switch (type) {
    case 'response':
      handleResponse(msg as unknown as RpcResponse)
      break
    case 'message_start': {
      const m = (msg as { message: AgentMessage }).message
      if (m.role === 'user') {
        handleUserMessageStart(m)
      } else if (m.role === 'assistant') {
        ensureAssistant(m)
      }
      break
    }
    case 'message_update':
      handleDelta((msg as { assistantMessageEvent: AssistantDelta }).assistantMessageEvent)
      break
    case 'message_end':
      finalizeAssistant((msg as { message: AgentMessage }).message)
      break
    case 'tool_execution_start':
      handleToolStart(msg as never)
      break
    case 'tool_execution_update':
      handleToolUpdate(msg as never)
      break
    case 'tool_execution_end':
      handleToolEnd(msg as never)
      break
    case 'agent_start':
      state.isStreaming = true
      status('Agent 开始处理')
      break
    case 'agent_end': {
      const willRetry = Boolean((msg as { willRetry?: boolean }).willRetry)
      status(willRetry ? '本轮结束，将自动重试' : '本轮结束', 'info')
      break
    }
    case 'agent_settled': {
      state.isStreaming = false
      // tool calls that never executed were cancelled
      for (const it of state.items) {
        if (it.kind === 'assistant') {
          for (const blk of it.blocks) {
            if (blk.kind === 'toolcall' && blk.tool && blk.tool.state === 'pending') {
              blk.tool.state = 'done'
              blk.tool.endedAt = Date.now()
            }
          }
        }
      }
      status('Agent 处理完成')
      break
    }
    case 'turn_start':
      status('新的一轮开始')
      break
    case 'turn_end': {
      const m = (msg as { message?: AgentMessage }).message
      if (m) finalizeAssistant(m)
      break
    }
    case 'queue_update': {
      const q = msg as { steering?: string[]; followUp?: string[] }
      state.steeringQueue = q.steering ?? []
      state.followUpQueue = q.followUp ?? []
      break
    }
    case 'compaction_start':
      status('上下文压缩中…', 'warn')
      break
    case 'compaction_end':
      status('上下文压缩完成')
      break
    case 'auto_retry_start': {
      const r = msg as { attempt?: number; maxAttempts?: number; errorMessage?: string }
      status(`自动重试 (${r.attempt}/${r.maxAttempts}): ${r.errorMessage ?? ''}`, 'warn')
      break
    }
    case 'auto_retry_end':
      status((msg as { success?: boolean }).success ? '重试成功' : '重试失败', 'info')
      break
    case 'bash_execution_update': {
      // direct RPC bash command output chunk
      const ev = msg as { id?: string; delta?: string }
      const last = state.items[state.items.length - 1]
      if (last && last.kind === 'status' && (last as { bashId?: string }).bashId === ev.id) {
        ;(last as { text: string }).text += ev.delta ?? ''
      } else {
        const it = { kind: 'status', key: keySeq++, text: ev.delta ?? '', tone: 'info', ts: Date.now(), bashId: ev.id }
        state.items.push(it as TimelineItem)
      }
      scrollToBottom()
      break
    }
    case 'extension_ui_request':
      handleUiRequest(msg as unknown as ExtensionUiRequest)
      break
    case 'extension_error': {
      const e = msg as { extensionPath?: string; error?: string }
      status(`扩展错误: ${e.error ?? ''} (${e.extensionPath ?? ''})`, 'error')
      break
    }
    default:
      break
  }
}

// ---------- public actions ----------

export function sendCommand(cmd: Record<string, unknown>): void {
  const ok = client.send(cmd)
  if (!ok) toast('连接未就绪，无法发送命令', 'error')
}

export function connect(action: 'create' | 'attach', sessionId?: string): void {
  const { settings } = state
  state.conn = 'connecting'
  state.connDetail = ''
  state.items = []
  client.connect(
    buildWsUrl(settings.gateway, settings.token, action, sessionId),
    {
      onOpen: () => {
        status(action === 'create' ? '连接已建立，等待会话就绪…' : '连接已建立，正在恢复会话…')
      },
      onMessage: onGatewayMessage,
      onClose: (code, reason) => {
        state.conn = 'disconnected'
        state.isStreaming = false
        const prev = state.activeSessionId
        if (code === 1011 && prev) {
          state.items.push({
            kind: 'status',
            key: keySeq++,
            text: `pi 进程异常退出 (${reason || '1011'})。重新 attach 会自动恢复。`,
            tone: 'error',
            ts: Date.now(),
          })
        } else if (code === 1000) {
          status('连接已关闭')
        } else {
          status(`连接关闭 (${code}: ${reason || 'no reason'})`, 'warn')
        }
        scrollToBottom()
      },
      onError: () => {
        state.conn = 'error'
        state.isStreaming = false
      },
    },
  )
}

export function newChat(): void {
  if (state.conn === 'connecting') return
  state.items = []
  connect('create')
}

export function attachSession(id: string): void {
  connect('attach', id)
}

export function disconnect(): void {
  client.disconnect()
  state.conn = 'disconnected'
  state.isStreaming = false
}

export function sendPrompt(text: string): void {
  const trimmed = text.trim()
  if (!trimmed) return
  addUserMessage(trimmed)
  const cmd: Record<string, unknown> = { type: 'prompt', message: trimmed }
  if (state.isStreaming) {
    cmd.streamingBehavior = 'steer'
  }
  sendCommand(cmd)
}

export function loadModelOptions(): void {
  sendCommand({ type: 'get_available_models' })
  sendCommand({ type: 'get_available_thinking_levels' })
}

export function setModel(model: ModelInfo): void {
  if (!model.provider || !model.id) return
  // 乐观更新，失败时 handleResponse 会回读状态
  state.currentModel = model
  state.model = formatModel(model)
  sendCommand({ type: 'set_model', provider: model.provider, modelId: model.id })
}

export function setThinkingLevel(level: string): void {
  state.thinkingLevel = level
  sendCommand({ type: 'set_thinking_level', level })
}

export function sendSteer(text: string): void {
  const trimmed = text.trim()
  if (!trimmed) return
  addUserMessage(trimmed)
  sendCommand({ type: 'steer', message: trimmed })
}

export function abort(): void {
  sendCommand({ type: 'abort' })
  status('已发送中止请求', 'warn')
}

export function addSession(id: string, name = ''): void {
  const existing = state.sessions.find((s) => s.id === id)
  if (existing) {
    existing.lastActive = Date.now()
    if (name) existing.name = name
  } else {
    state.sessions.push({ id, name, createdAt: Date.now(), lastActive: Date.now() })
  }
  save(SESSIONS_KEY, state.sessions)
}

export function renameSession(id: string, name: string): void {
  const s = state.sessions.find((x) => x.id === id)
  if (s) {
    s.name = name
    save(SESSIONS_KEY, state.sessions)
  }
  if (id === state.activeSessionId) {
    state.sessionName = name
    sendCommand({ type: 'set_session_name', name })
  }
}

export function removeSession(id: string): void {
  state.sessions = state.sessions.filter((s) => s.id !== id)
  save(SESSIONS_KEY, state.sessions)
  if (state.activeSessionId === id) {
    state.activeSessionId = ''
    localStorage.removeItem(ACTIVE_KEY)
    disconnect()
  }
}

export function saveSettings(s: Settings): void {
  state.settings = { ...s }
  save(SETTINGS_KEY, state.settings)
}

export function openSettings(): void {
  state.settingsOpen = true
}

export function closeSettings(): void {
  state.settingsOpen = false
}

export function updateState(cb: (s: typeof state) => void): void {
  cb(state)
}

export function useStore() {
  return {
    // readonly at runtime (warns on mutation), typed as mutable for ergonomic templates
    state: readonly(state) as typeof state,
    connect,
    newChat,
    attachSession,
    disconnect,
    sendPrompt,
    sendSteer,
    abort,
    setModel,
    setThinkingLevel,
    loadModelOptions,
    renameSession,
    removeSession,
    saveSettings,
    respondDialog,
    sendCommand,
    addSession,
    openSettings,
    closeSettings,
  }
}

// initial auto-attach: restore the last active session
const initialSession = state.activeSessionId
if (initialSession && loadSessions().some((s) => s.id === initialSession)) {
  connect('attach', initialSession)
}

// Protocol types for pi2ws / pi RPC mode (see pi packages/coding-agent/docs/rpc.md)

export interface GatewayEvent {
  type: 'pi2ws'
  event: 'ready' | 'error'
  action?: 'create' | 'attach'
  session_id?: string
  code?: string
  message?: string
}

export interface RpcResponse {
  id?: string
  type: 'response'
  command: string
  success: boolean
  data?: Record<string, unknown>
  error?: string
}

// ---- streaming deltas (assistantMessageEvent) ----

export type AssistantDelta =
  | { type: 'start'; partial: Record<string, unknown> }
  | { type: 'text_start'; contentIndex: number; partial: Record<string, unknown> }
  | { type: 'text_delta'; contentIndex: number; delta: string; partial: Record<string, unknown> }
  | { type: 'text_end'; contentIndex: number; content: string; partial: Record<string, unknown> }
  | { type: 'thinking_start'; contentIndex: number; partial: Record<string, unknown> }
  | { type: 'thinking_delta'; contentIndex: number; delta: string; partial: Record<string, unknown> }
  | { type: 'thinking_end'; contentIndex: number; content: string; partial: Record<string, unknown> }
  | { type: 'toolcall_start'; contentIndex: number; partial: Record<string, unknown> }
  | { type: 'toolcall_delta'; contentIndex: number; delta: string; partial: Record<string, unknown> }
  | { type: 'toolcall_end'; contentIndex: number; toolCall: ToolCall; partial: Record<string, unknown> }
  | { type: 'done'; reason: string; message: Record<string, unknown> }
  | { type: 'error'; reason: string; error: Record<string, unknown> }

export interface MessageUpdate {
  type: 'message_update'
  message: AgentMessage
  assistantMessageEvent: AssistantDelta
}

// ---- agent events ----

export interface ToolCall {
  id: string
  name: string
  arguments: Record<string, unknown> | string
}

export interface AgentMessage {
  role: 'user' | 'assistant' | 'toolResult' | 'bashExecution' | string
  content?: string | ContentBlock[]
  toolCallId?: string
  toolName?: string
  isError?: boolean
  model?: string
  stopReason?: string
  timestamp?: number
  command?: string
  output?: string
  exitCode?: number
  id?: string
  [key: string]: unknown
}

export interface ContentBlock {
  type: 'text' | 'thinking' | 'toolCall' | 'image' | string
  text?: string
  thinking?: string
  id?: string
  name?: string
  arguments?: Record<string, unknown> | string
  data?: string
  mimeType?: string
  [key: string]: unknown
}

export interface ToolExecutionStart {
  type: 'tool_execution_start'
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
}

export interface ToolExecutionUpdate {
  type: 'tool_execution_update'
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
  partialResult?: { content?: ContentBlock[]; details?: Record<string, unknown> }
}

export interface ToolExecutionEnd {
  type: 'tool_execution_end'
  toolCallId: string
  toolName: string
  result?: { content?: ContentBlock[]; details?: Record<string, unknown> }
  isError?: boolean
}

// ---- extension UI ----

export type UiDialogMethod = 'select' | 'confirm' | 'input' | 'editor'

export interface ExtensionUiRequest {
  type: 'extension_ui_request'
  id: string
  method: UiDialogMethod | 'notify' | 'setStatus' | 'setWidget' | 'setTitle' | 'set_editor_text'
  title?: string
  message?: string
  options?: string[]
  placeholder?: string
  prefill?: string
  timeout?: number
  text?: string
  status?: string
}

// ---- timeline view models ----

export interface ToolCallView {
  /** toolCallId once known (from toolcall_end) */
  id: string
  name: string
  args: string
  argsDone: boolean
  output: string
  outputDone: boolean
  state: 'streaming' | 'pending' | 'running' | 'done' | 'error'
  isError: boolean
  startedAt: number
  endedAt?: number
}

export interface AssistantBlock {
  kind: 'thinking' | 'text' | 'toolcall'
  text?: string
  tool?: ToolCallView
}

export interface AssistantItem {
  kind: 'assistant'
  key: number
  blocks: AssistantBlock[]
  model?: string
  stopReason?: string
  streaming: boolean
  ts: number
}

export function assistantThinking(item: AssistantItem): string {
  return item.blocks.filter((b) => b.kind === 'thinking').map((b) => b.text ?? '').join('\n')
}

export function assistantText(item: AssistantItem): string {
  return item.blocks.filter((b) => b.kind === 'text').map((b) => b.text ?? '').join('\n\n')
}

export function assistantTools(item: AssistantItem): ToolCallView[] {
  return item.blocks.filter((b) => b.kind === 'toolcall').map((b) => b.tool as ToolCallView)
}

export interface UserItem {
  kind: 'user'
  key: number
  text: string
  ts: number
}

export interface ToolItem {
  kind: 'tool'
  key: number
  tool: ToolCallView
}

export interface StatusItem {
  kind: 'status'
  key: number
  text: string
  tone: 'info' | 'warn' | 'error'
  ts: number
}

export type TimelineItem = UserItem | AssistantItem | ToolItem | StatusItem

export interface ModelInfo {
  id: string
  name?: string
  provider?: string
}

export interface SavedSession {
  id: string
  name: string
  createdAt: number
  lastActive: number
  /** Workspace (pi working directory) reported by the gateway ready event. */
  workDir?: string
}

export interface Settings {
  gateway: string
  token: string
}

export interface UiDialogState {
  request: ExtensionUiRequest
  value: string
  open: boolean
}

export interface Toast {
  id: number
  text: string
  kind: 'info' | 'error' | 'success'
}

export type ConnState = 'disconnected' | 'connecting' | 'ready' | 'error'

export function toolNameOf(name: string): string {
  return name || 'tool'
}

export function formatTime(ts: number): string {
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

export function formatRelative(ts: number): string {
  const diff = Date.now() - ts
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 7) return `${d} 天前`
  const dte = new Date(ts)
  return `${dte.getFullYear()}/${dte.getMonth() + 1}/${dte.getDate()}`
}

export function formatDuration(from: number, to?: number): string {
  const ms = (to ?? Date.now()) - from
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

export function contentText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((b) => (b && typeof b === 'object' ? (b.text ?? b.thinking ?? '') : String(b)))
      .join('\n')
  }
  return ''
}

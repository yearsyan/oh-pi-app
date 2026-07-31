// Thin WebSocket wrapper around the pi2ws gateway protocol.

export type WsStatus = 'idle' | 'connecting' | 'open' | 'closed' | 'error'

export interface WsHandlers {
  onOpen?: () => void
  onMessage?: (msg: Record<string, unknown>) => void
  onClose?: (code: number, reason: string) => void
  onError?: (message: string) => void
}

export class Pi2wsClient {
  private ws: WebSocket | null = null
  status: WsStatus = 'idle'

  connect(url: string, handlers: WsHandlers): void {
    this.disconnect()
    this.status = 'connecting'
    try {
      this.ws = new WebSocket(url)
    } catch (err) {
      this.status = 'error'
      handlers.onError?.(err instanceof Error ? err.message : String(err))
      return
    }
    this.ws.onopen = () => {
      this.status = 'open'
      handlers.onOpen?.()
    }
    this.ws.onmessage = (ev) => {
      let msg: Record<string, unknown>
      try {
        msg = JSON.parse(String(ev.data))
      } catch {
        return
      }
      handlers.onMessage?.(msg)
    }
    this.ws.onclose = (ev) => {
      this.status = 'closed'
      handlers.onClose?.(ev.code, ev.reason)
    }
    this.ws.onerror = () => {
      this.status = 'error'
      handlers.onError?.('WebSocket 连接出错')
    }
  }

  send(obj: unknown): boolean {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false
    this.ws.send(JSON.stringify(obj))
    return true
  }

  disconnect(): void {
    if (this.ws) {
      this.ws.onopen = null
      this.ws.onmessage = null
      this.ws.onclose = null
      this.ws.onerror = null
      try {
        this.ws.close()
      } catch {
        /* noop */
      }
      this.ws = null
    }
    this.status = 'idle'
  }
}

export function buildWsUrl(gateway: string, token: string, action: 'create' | 'attach', sessionId?: string): string {
  let base = (gateway ?? '').trim()
  if (!base) {
    // 留空：走同源代理（vite dev server 的 /ws 反代，或网关同源部署）
    base = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`
  }
  const url = new URL(base)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws'
  url.search = ''
  url.searchParams.set('action', action)
  if (sessionId) url.searchParams.set('session_id', sessionId)
  if (token) url.searchParams.set('token', token)
  return url.toString()
}

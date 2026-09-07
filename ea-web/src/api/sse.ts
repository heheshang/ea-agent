/** Authenticated SSE subscription for named agent chat events. */
export type SseEventName =
  | 'plan'
  | 'thinking_delta'
  | 'tool_call'
  | 'action_result'
  | 'text_delta'
  | 'done'
  | 'error'

export type SseHandler = (event: SseEventName, data: unknown) => void

export interface SseSubscription {
  close(): void
}

const EVENT_NAMES: SseEventName[] = [
  'plan',
  'thinking_delta',
  'tool_call',
  'action_result',
  'text_delta',
  'done',
  'error',
]

export function subscribeRun(runId: number, onEvent: SseHandler): SseSubscription {
  const controller = new AbortController()
  let closed = false
  const subscription: SseSubscription = {
    close() {
      closed = true
      controller.abort()
    },
  }
  const emit = (name: SseEventName, data: unknown) => {
    if (closed) return
    if (name === 'done' || name === 'error') subscription.close()
    onEvent(name, data)
  }

  async function consume() {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
    try {
      const headers: Record<string, string> = {
        Accept: 'text/event-stream',
        'X-Tenant-Id': localStorage.getItem('ea:tenantId') ?? '',
        'X-Request-Id': crypto.randomUUID(),
      }
      const token = localStorage.getItem('ea:token')
      if (token) headers.Authorization = `Bearer ${token}`
      const base = (import.meta.env.VITE_API_BASE ?? '/api').replace(/\/$/, '')
      const response = await fetch(`${base}/agent/chat?request_id=${encodeURIComponent(runId)}`, {
        headers,
        signal: controller.signal,
      })
      if (closed) return
      if (!response.ok) throw new Error(`SSE 请求失败 (${response.status})`)
      if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('text/event-stream') || !response.body) {
        throw new Error('SSE 响应格式无效')
      }
      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let line = ''
      let skipLf = false
      let event = ''
      let data: string[] = []
      const acceptLine = () => {
        if (line === '') {
          if (data.length && EVENT_NAMES.includes(event as SseEventName)) {
            const raw = data.join('\n')
            let payload: unknown = raw
            try { payload = JSON.parse(raw) } catch { /* Preserve non-JSON event data. */ }
            emit(event as SseEventName, payload)
          }
          event = ''
          data = []
        } else if (!line.startsWith(':')) {
          const colon = line.indexOf(':')
          const field = colon < 0 ? line : line.slice(0, colon)
          let value = colon < 0 ? '' : line.slice(colon + 1)
          if (value.startsWith(' ')) value = value.slice(1)
          if (field === 'event') event = value
          else if (field === 'data') data.push(value)
        }
        line = ''
      }
      const acceptChunk = (chunk: string) => {
        let start = 0
        for (let i = 0; i < chunk.length && !closed; i++) {
          const char = chunk[i]
          if (skipLf) {
            skipLf = false
            if (char === '\n') {
              start = i + 1
              continue
            }
          }
          if (char === '\r' || char === '\n') {
            line += chunk.slice(start, i)
            acceptLine()
            skipLf = char === '\r'
            start = i + 1
          }
        }
        if (!closed) line += chunk.slice(start)
      }
      while (!closed) {
        const result = await reader.read()
        if (closed) return
        acceptChunk(decoder.decode(result.value, { stream: !result.done }))
        if (result.done) {
          if (!closed) throw new Error('SSE 连接在完成前中断')
          break
        }
      }
    } catch (error) {
      emit('error', { message: error instanceof Error ? error.message : 'SSE 连接中断' })
    } finally {
      if (reader) {
        try { await reader.cancel() } catch { /* The fetch may already have been aborted. */ }
        reader.releaseLock()
      }
    }
  }
  void consume()
  return subscription
}

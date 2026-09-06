/**
 * SSE 订阅（7.4）：EventSource 原生 GET /api/agent/chat?request_id=…；
 * 服务端按事件名（plan/thinking_delta/tool_call/action_result/text_delta/done/error）发送命名事件。
 */
export type SseEventName =
  | 'plan'
  | 'thinking_delta'
  | 'tool_call'
  | 'action_result'
  | 'text_delta'
  | 'done'
  | 'error'

export type SseHandler = (event: SseEventName, data: unknown) => void

const EVENT_NAMES: SseEventName[] = [
  'plan',
  'thinking_delta',
  'tool_call',
  'action_result',
  'text_delta',
  'done',
  'error',
]

export function subscribeRun(runId: number, onEvent: SseHandler): EventSource {
  const es = new EventSource(`/api/agent/chat?request_id=${runId}`)
  for (const name of EVENT_NAMES) {
    es.addEventListener(name, (ev: MessageEvent) => {
      let data: unknown = ev.data
      try {
        data = JSON.parse(String(ev.data))
      } catch {
        // 非 JSON 原样透传
      }
      onEvent(name, data)
    })
  }
  es.onerror = () => {
    onEvent('error', { message: 'SSE 连接中断' })
    es.close()
  }
  return es
}
<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/http'
import { subscribeRun, type SseEventName } from '../api/sse'
import type { AgentRun } from '../api/types'

defineOptions({ name: 'AgentWorkbenchView' })

type Block =
  | { kind: 'thinking'; text: string }
  | { kind: 'step'; title: string; detail?: unknown }
  | { kind: 'reply'; text: string }

interface ChatMessage {
  role: 'user' | 'assistant'
  text?: string
  blocks: Block[]
}

const goal = ref('查询最近活跃客户并发送优惠')
const running = ref(false)
const runId = ref<number | null>(null)
const runStatus = ref('')
const runs = ref<AgentRun[]>([])
const messages = ref<ChatMessage[]>([])
const thinkingOpen = ref<string[]>(['t'])
const chatBox = ref<HTMLDivElement | null>(null)
let es: EventSource | null = null

/** 运行中无任何助手输出时的占位（首块到达前）。 */
const awaitingFirst = computed(() => running.value && messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'user')

const currentRunLabel = computed(() => (runId.value ? `Run #${runId.value} · ${runStatus.value}` : ''))

function str(v: unknown): string {
  return v == null ? '' : String(v)
}

/** 事件累积：thinking/text 聚合进同一块，工具调用成步骤卡片，plan 置顶。 */
function pushEvent(name: SseEventName, data: unknown) {
  const d = (data ?? {}) as Record<string, unknown>
  if (name === 'done') {
    runStatus.value = 'DONE'
    running.value = false
    thinkingOpen.value = []
    es?.close()
    refreshRuns()
    rebuildFromHistory()
    return
  }
  if (name === 'error') {
    runStatus.value = 'ERROR'
    running.value = false
    thinkingOpen.value = []
    es?.close()
    refreshAfterError()
    return
  }
  let asst = messages.value[messages.value.length - 1]
  if (!asst || asst.role !== 'assistant') {
    asst = { role: 'assistant', blocks: [] }
    messages.value.push(asst)
  }
  const last = asst.blocks[asst.blocks.length - 1]
  switch (name) {
    case 'thinking_delta': {
      const text = str(d.text)
      if (!text) return
      if (last?.kind === 'thinking') last.text += text
      else asst.blocks.push({ kind: 'thinking', text })
      break
    }
    case 'tool_call':
      asst.blocks.push({ kind: 'step', title: `工具调用 · ${str(d.tool ?? 'unknown')}`, detail: d.args })
      break
    case 'action_result':
      asst.blocks.push({ kind: 'step', title: `工具结果 · ${str(d.tool ?? 'tool')}`, detail: d.result ?? d })
      break
    case 'plan':
      if (Array.isArray(d.steps)) asst.blocks.unshift({ kind: 'step', title: '计划', detail: d.steps })
      break
    case 'text_delta': {
      const text = str(d.text)
      if (!text) return
      if (last?.kind === 'reply') last.text += text
      else asst.blocks.push({ kind: 'reply', text })
      break
    }
  }
}

async function start() {
  if (!goal.value.trim()) {
    ElMessage.warning('请输入目标')
    return
  }
  running.value = true
  // 不清空历史：追加本轮用户消息
  messages.value.push({ role: 'user', text: goal.value, blocks: [] })
  try {
    const sessionId = localStorage.getItem('ea.session_id')
    const r = await post<{ run_id: number; status: string; session_id: string }>(
      '/agent/chat', { goal: goal.value },
      sessionId ? { headers: { 'X-Session-Id': sessionId } } : undefined)
    if (r.session_id) localStorage.setItem('ea.session_id', r.session_id)
    runId.value = r.run_id
    runStatus.value = r.status
    es?.close()
    es = subscribeRun(r.run_id, pushEvent)
  } catch {
    running.value = false
  }
}

function stop() {
  es?.close()
  es = null
  running.value = false
}

function replay(run: AgentRun) {
  runId.value = run.id
  runStatus.value = run.status
  // 不清空历史：追加回放轮次的用户消息
  messages.value.push({ role: 'user', text: run.goal ?? '（历史 Run）', blocks: [] })
  running.value = true
  es?.close()
  es = subscribeRun(run.id, pushEvent)
}

async function refreshRuns() {
  runs.value = await get<AgentRun[]>('/agent/runs', { limit: 20 })
}

/** 当前会话历史轮次（按 id 升序）；后端暂未支持 session_id 参数时按 run.sessionId 本地过滤兜底。 */
async function loadSessionHistory(): Promise<AgentRun[]> {
  const sessionId = localStorage.getItem('ea.session_id')
  if (!sessionId) return []
  try {
    const rs = await get<AgentRun[]>('/agent/runs', { limit: 200, session_id: sessionId })
    return rs.filter(r => r.sessionId === sessionId).sort((a, b) => a.id - b.id)
  } catch {
    return []
  }
}

/** 历史 runs → 对话消息：每条 = user(goal) + assistant(summary；空则状态占位)。 */
function renderHistory(runs: AgentRun[]): ChatMessage[] {
  const out: ChatMessage[] = []
  for (const r of runs) {
    out.push({ role: 'user', text: r.goal ?? '（历史 Run）', blocks: [] })
    const text = r.summary?.trim() ? r.summary : `[${r.status}] 无回复`
    out.push({ role: 'assistant', blocks: [{ kind: 'reply', text }] })
  }
  return out
}

/** 页面载入/onActivated：拉取当前会话历史并渲染为对话消息；运行中不打扰。 */
async function loadSessionHistoryIntoMessages() {
  if (running.value) return
  const history = await loadSessionHistory()
  if (history.length) messages.value = renderHistory(history)
}

/** done 后：以最新 runs 重建消息 = 历史 + 本轮（本轮 assistant 用 summary 呈现；空则状态占位）。 */
async function rebuildFromHistory() {
  const history = await loadSessionHistory()
  if (!history.length) return
  const msgs = renderHistory(history)
  const cur = history.find(r => r.id === runId.value)
  if (cur) {
    runStatus.value = cur.status
  } else if (runId.value != null) {
    // 本轮 run 不在当前会话历史（如回放其它会话）→ 保留刚流式生成的本轮消息
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant') msgs.push(last)
  }
  messages.value = msgs
}

/** error 后：保留已流式内容，仅按后端最新状态刷新展示（如 SSE 断线但后端实际完成）。 */
async function refreshAfterError() {
  await refreshRuns()
  const cur = runs.value.find(r => r.id === runId.value)
  if (cur) runStatus.value = cur.status
}

refreshRuns()
loadSessionHistoryIntoMessages()
onActivated(() => {
  refreshRuns()
  loadSessionHistoryIntoMessages()
})
onBeforeUnmount(() => es?.close())

watch(messages, () => {
  nextTick(() => {
    chatBox.value?.scrollTo({ top: chatBox.value.scrollHeight, behavior: 'smooth' })
  })
}, { deep: true })

function pretty(data: unknown): string {
  if (data == null) return ''
  if (typeof data === 'string') return data
  return JSON.stringify(data, null, 2)
}
</script>

<template>
  <div class="wb">
    <div v-if="currentRunLabel" class="run-label">
      <span class="run-chip">{{ currentRunLabel }}</span>
    </div>

    <el-row :gutter="16" class="wb-row">
      <!-- 对话主区 -->
      <el-col :span="15">
        <div class="chat-card">
          <div v-if="messages.length === 0" class="chat-empty">
            <el-empty description="发起对话后，这里展示 Agent 的思考、工具调用与回复" />
          </div>
          <div v-else ref="chatBox" class="chat-box">
            <div v-for="(m, mi) in messages" :key="mi" class="msg-row" :class="m.role">
              <div v-if="m.role === 'user'" class="bubble user">{{ m.text }}</div>
              <div v-else class="row-inner">
                <span class="a-avatar">AI</span>
                <div class="bubble assistant">
                  <template v-for="(b, bi) in m.blocks" :key="bi">
                    <el-collapse v-if="b.kind === 'thinking' && b.text" v-model="thinkingOpen" class="thinking">
                      <el-collapse-item :title="`思考过程 · ${b.text.length} 字`" name="t">
                        <p class="thinking-text">{{ b.text }}</p>
                      </el-collapse-item>
                    </el-collapse>
                    <div v-else-if="b.kind === 'step'" class="step">
                      <div class="step-title">
                        <el-tag size="small" effect="plain">{{ b.title }}</el-tag>
                      </div>
                      <pre v-if="b.detail != null">{{ pretty(b.detail) }}</pre>
                    </div>
                    <p v-else-if="b.kind === 'reply'" class="reply">{{ b.text }}</p>
                  </template>
                </div>
              </div>
            </div>
            <div v-if="awaitingFirst" class="msg-row assistant">
              <div class="row-inner">
                <span class="a-avatar">AI</span>
                <div class="bubble assistant typing">
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部输入（豆包式：输入在对话下方） -->
        <div class="input-card">
          <el-input
            v-model="goal"
            size="large"
            placeholder="输入目标，例如：查询最近活跃的客户并发送优惠"
            :disabled="running"
            @keyup.enter="running ? stop() : start()"
          >
            <template #append>
              <el-button class="send-btn" :loading="running" @click="running ? stop() : start()">
                {{ running ? '停止' : '发起' }}
              </el-button>
            </template>
          </el-input>
        </div>
      </el-col>

      <!-- 历史 Run -->
      <el-col :span="9">
        <el-card shadow="never" header="历史 Run" class="runs-card">
          <div v-if="runs.length === 0" class="no-runs">暂无记录</div>
          <div v-for="r in runs" :key="r.id" class="run-item" @click="replay(r)">
            <div class="run-line">
              <el-tag size="small" :type="r.status === 'COMPLETED' ? 'success' : r.status === 'FAILED' ? 'danger' : 'info'">
                {{ r.status }}
              </el-tag>
              <span class="run-id">#{{ r.id }}</span>
              <span class="run-time">{{ r.createdAt }}</span>
            </div>
            <div class="run-goal">{{ r.goal }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.wb {
  max-width: 1180px;
  margin: 0 auto;
}
.run-label {
  margin-bottom: 12px;
}
.run-chip {
  display: inline-block;
  background: #e9f0ff;
  color: #3370ff;
  font-size: 13px;
  font-weight: 500;
  padding: 5px 14px;
  border-radius: 999px;
}
.chat-card {
  background: #fff;
  border-radius: 20px;
  box-shadow: var(--db-card-shadow);
  padding: 20px 20px 8px;
  margin-bottom: 14px;
  min-height: 360px;
  display: flex;
  flex-direction: column;
}
.chat-empty {
  min-height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
}
.chat-box {
  max-height: 56vh;
  overflow-y: auto;
  padding: 4px 6px 12px;
  flex: 1;
}
.msg-row {
  display: flex;
  margin-bottom: 16px;
}
.msg-row.user {
  justify-content: flex-end;
}
.msg-row.assistant {
  justify-content: flex-start;
}
.row-inner {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 92%;
}
.a-avatar {
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, #3370ff, #5c8bff);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
  box-shadow: 0 3px 10px rgba(51, 112, 255, 0.3);
}
.bubble {
  line-height: 1.65;
  font-size: 14px;
}
.bubble.user {
  background: linear-gradient(135deg, #3370ff, #5c8bff);
  color: #fff;
  border-radius: 18px 18px 6px 18px;
  padding: 10px 16px;
  max-width: 86%;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 3px 12px rgba(51, 112, 255, 0.25);
}
.bubble.assistant {
  background: #f7f8fa;
  border-radius: 18px 18px 18px 6px;
  padding: 12px 16px;
  color: #1d2129;
  flex: 1;
  min-width: 0;
}
.thinking {
  margin-bottom: 8px;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
}
.thinking :deep(.el-collapse-item__header) {
  background: transparent;
  height: 32px;
  font-size: 12px;
  color: #86909c;
  padding-left: 12px;
}
.thinking :deep(.el-collapse-item__wrap) {
  background: #fff;
  border-bottom: none;
}
.thinking-text {
  margin: 0;
  color: #4e5969;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
  padding: 0 12px 12px;
}
.step {
  margin-bottom: 8px;
}
.step-title {
  margin-bottom: 4px;
}
.step-title :deep(.el-tag) {
  border-radius: 999px;
  border-color: #d5e2ff;
  color: #3370ff;
  background: #f4f8ff;
}
.reply {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.typing {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 12px 16px;
  background: #f7f8fa;
}
.typing .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #3370ff;
  animation: blink 1.2s infinite;
}
.typing .dot:nth-child(2) {
  animation-delay: 0.2s;
}
.typing .dot:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.3; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
pre {
  background: #fafbfc;
  border: 1px solid #f0f1f3;
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 12px;
  overflow: auto;
  max-height: 200px;
  margin: 0;
  color: #4e5969;
}
.input-card {
  background: #fff;
  border-radius: 20px;
  box-shadow: var(--db-card-shadow);
  padding: 12px;
}
.input-card :deep(.el-input__wrapper) {
  border-radius: 14px;
  padding: 2px 14px;
}
.send-btn {
  border-radius: 999px;
  margin: 0 4px;
  padding: 0 24px;
  background: linear-gradient(135deg, #3370ff, #5c8bff);
  border: none;
  color: #fff;
  box-shadow: 0 3px 10px rgba(51, 112, 255, 0.3);
}
.send-btn:hover {
  color: #fff;
  opacity: 0.92;
}
.no-runs {
  color: #909399;
  font-size: 13px;
  text-align: center;
  padding: 24px 0;
}
.run-item {
  padding: 10px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.2s;
}
.run-item:hover {
  background: #f4f8ff;
}
.run-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.run-id {
  font-weight: 600;
  font-size: 13px;
}
.run-time {
  color: #c0c4cc;
  font-size: 12px;
  margin-left: auto;
}
.run-goal {
  margin-top: 4px;
  color: #4e5969;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
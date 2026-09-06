<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/http'
import { subscribeRun, type SseEventName } from '../api/sse'
import type { AgentRun } from '../api/types'
import { renderMarkdown } from '../utils/markdown'

defineOptions({ name: 'AgentWorkbenchView' })

type Block =
  | { kind: 'thinking'; text: string }
  | { kind: 'step'; title: string; detail?: unknown }
  | { kind: 'reply'; text: string }
  | { kind: 'approval'; approvalId: string; action: string; args?: unknown }

interface ChatMessage {
  role: 'user' | 'assistant'
  text?: string
  blocks: Block[]
  /** 该助手消息是否仍在 SSE 流式生成中（期间 reply 以纯文本展示，避免未闭合 markdown 闪烁）。 */
  streaming?: boolean
}

const goal = ref('查询最近活跃客户并发送优惠')
const mode = ref<'auto' | 'suggest'>('auto')
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

interface OntologyNode {
  id: string
  type: string
  label: string
}
interface OntologyEdge {
  from: string
  to: string
}

/** Ontology 链路映射（模块级缓存，惰性加载一次；失败静默，标题退化为无链路）。 */
let ontologyLoaded = false
let ontologyLoading: Promise<void> | null = null
const actionObj: Record<string, string> = {}
const functionObj: Record<string, string> = {}
const objLabel: Record<string, string> = {}

function ensureOntology(): Promise<void> {
  if (ontologyLoaded) return Promise.resolve()
  if (ontologyLoading) return ontologyLoading
  ontologyLoading = get<{ nodes: OntologyNode[]; edges: OntologyEdge[] }>('/agent/stats/ontology-graph', { days: 30 })
    .then((g) => {
      for (const n of g?.nodes ?? []) {
        if (n.type === 'object' && n.id.startsWith('obj:')) objLabel[n.id.slice(4)] = n.label
      }
      for (const e of g?.edges ?? []) {
        if (e.from.startsWith('action:') && e.to.startsWith('obj:')) actionObj[e.from.slice(7)] = e.to.slice(4)
        else if (e.from.startsWith('function:') && e.to.startsWith('obj:')) functionObj[e.from.slice(9)] = e.to.slice(4)
      }
      ontologyLoaded = true
    })
    .catch(() => { /* 静默失败：链路缺失时标题保持原样 */ })
    .finally(() => { ontologyLoading = null })
  return ontologyLoading
}

/** 按 action/function 名解析对象 label；解析不到返回空串。 */
function chainSuffix(chain: Record<string, unknown> | undefined, args: Record<string, unknown> | undefined): string {
  const action = typeof chain?.action === 'string' ? chain.action : typeof args?.action === 'string' ? args.action : ''
  const fn = typeof chain?.function === 'string' ? chain.function : typeof args?.name === 'string' ? args.name : ''
  if (action) {
    const obj = actionObj[action]
    const label = obj ? objLabel[obj] ?? '' : ''
    return label ? ` → ${action} · ${label}` : ` → ${action}`
  }
  if (fn) {
    const obj = functionObj[fn]
    const label = obj ? objLabel[obj] ?? '' : ''
    return label ? ` → ${fn} · ${label}` : ` → ${fn}`
  }
  return ''
}

/** 事件累积：thinking/text 聚合进同一块，工具调用成步骤卡片，plan 置顶。 */
function pushEvent(name: SseEventName, data: unknown) {
  const d = (data ?? {}) as Record<string, unknown>
  if (name === 'done') {
    const lastAsst = messages.value[messages.value.length - 1]
    if (lastAsst?.role === 'assistant') lastAsst.streaming = false
    runStatus.value = 'DONE'
    running.value = false
    thinkingOpen.value = []
    es?.close()
    refreshRuns()
    rebuildFromHistory()
    return
  }
  if (name === 'error') {
    const lastAsst = messages.value[messages.value.length - 1]
    if (lastAsst?.role === 'assistant') lastAsst.streaming = false
    runStatus.value = 'ERROR'
    running.value = false
    thinkingOpen.value = []
    es?.close()
    refreshAfterError()
    return
  }
  let asst = messages.value[messages.value.length - 1]
  if (!asst || asst.role !== 'assistant') {
    asst = { role: 'assistant', blocks: [], streaming: true }
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
    case 'tool_call': {
      ensureOntology()
      const args = (d.args ?? {}) as Record<string, unknown>
      const suffix = chainSuffix(undefined, args)
      asst.blocks.push({ kind: 'step', title: `工具调用 · ${str(d.tool ?? 'unknown')}${suffix}`, detail: d.args })
      break
    }
    case 'action_result': {
      ensureOntology()
      const chain = (d.chain ?? {}) as Record<string, unknown>
      const suffix = chainSuffix(chain, undefined)
      asst.blocks.push({ kind: 'step', title: `工具结果 · ${str(d.tool ?? 'unknown')}${suffix}`, detail: d.result ?? d })
      // 会话 HITL：applyAction 在建议模式返回 PENDING_APPROVAL → 聊天流内渲染待确认卡片（回复确认/取消由 approveAction 放行）
      const parsed = parseApprovalResult(d.result)
      if (parsed) {
        asst.blocks.push({
          kind: 'approval',
          approvalId: str(parsed.approval_id),
          action: str(parsed.action ?? d.tool ?? 'applyAction'),
          args: parsed.args,
        })
      }
      break
    }
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
    await ensureOntology()
    const sessionId = localStorage.getItem('ea.session_id')
    const r = await post<{ run_id: number; status: string; session_id: string }>(
      '/agent/chat', { goal: goal.value, mode: mode.value },
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
  ensureOntology().then(() => {
    es = subscribeRun(run.id, pushEvent)
  })
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

/** done 后：以最新 runs 重建消息 = 历史 + 本轮；本轮刚流式完成，保留完整 live 内容（思考/步骤/全文回复）不被 summary 摘要取代。 */
async function rebuildFromHistory() {
  const history = await loadSessionHistory()
  if (!history.length) return
  const msgs = renderHistory(history)
  const cur = history.find(r => r.id === runId.value)
  if (cur) {
    runStatus.value = cur.status
    // 本轮是会话内最新 run（renderHistory 末条 assistant 即它）→ 用 live 完整块替换 summary 条目
    const live = messages.value[messages.value.length - 1]
    if (live?.role === 'assistant' && live.blocks.length) msgs[msgs.length - 1] = live
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
onBeforeUnmount(() => {
  es?.close()
})

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

/** 解析 action_result：applyAction 建议模式挂起 → {approval_id, action, args}；非挂起返回 null。 */
function parseApprovalResult(raw: unknown): Record<string, unknown> | null {
  if (raw == null) return null
  if (typeof raw === 'object') {
    const o = raw as Record<string, unknown>
    return o.status === 'PENDING_APPROVAL' && o.approval_id ? o : null
  }
  const s = String(raw)
  if (!s.includes('PENDING_APPROVAL')) return null
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    return o.status === 'PENDING_APPROVAL' && o.approval_id ? o : null
  } catch {
    // Java Map toString 兜底：{ok=false, status=PENDING_APPROVAL, approval_id=xxx, action=yyy, args={…}}
    const grab = (k: string): string => {
      const i = s.indexOf(k + '=')
      if (i < 0) return ''
      const rest = s.slice(i + k.length + 1)
      return rest.split(',').filter(Boolean).shift()?.trim() ?? ''
    }
    const id = grab('approval_id')
    const action = grab('action')
    return id ? { status: 'PENDING_APPROVAL', approval_id: id, action: action || undefined } : null
  }
}

/** 会话 HITL：applyAction 挂起后由用户在聊天内回复确认/拒绝，LLM 调 approveAction 放行（无需前端 API）。 */
</script>

<template>
  <div class="wb">
    <div v-if="currentRunLabel" class="run-label">
      <span class="run-chip">{{ currentRunLabel }}</span>
    </div>

    <div class="wb-toolbar">
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="auto">auto（直接执行）</el-radio-button>
        <el-radio-button value="suggest">suggest（写动作待确认）</el-radio-button>
      </el-radio-group>
    </div>

    <el-row :gutter="16" class="wb-row">
      <!-- 对话主区 -->
      <el-col :span="15" class="col-chat">
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
                      <div v-else-if="b.kind === 'approval'" class="approval-card">
                        <div class="approval-title">待确认（会话 HITL） · {{ b.action }}</div>
                        <pre v-if="b.args != null" class="approval-args">{{ pretty(b.args) }}</pre>
                        <div class="approval-hint">请在聊天中回复「确认执行」或「取消」，由助手放行后执行</div>
                      </div>
                      <p v-else-if="b.kind === 'reply' && m.streaming" class="reply">{{ b.text }}</p>
                      <div v-else-if="b.kind === 'reply'" class="reply markdown-body" v-html="renderMarkdown(b.text)" />
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
      <el-col :span="9" class="col-runs">
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
  /* 整屏对话：聊天区撑满剩余高度，输入条固定钉底（56=header，40=main 上下 padding） */
  height: calc(100vh - 96px);
  min-height: 480px;
  display: flex;
  flex-direction: column;
}
.wb-row {
  flex: 1;
  min-height: 0;
}
.col-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.run-label {
  margin-bottom: 12px;
}
.wb-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.approval-args {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
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
  min-height: 0;
  flex: 1;
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
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 6px 12px;
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
/* 聊天流 HITL 门控卡片：建议模式挂起动作，聊天内确认/取消。 */
.approval-card {
  margin: 10px 0 4px;
  padding: 12px 14px;
  border: 1px solid #ffe1a8;
  border-radius: 12px;
  background: #fffbf2;
}
.approval-title {
  font-size: 14px;
  font-weight: 600;
  color: #b25e00;
  margin-bottom: 8px;
}
.approval-args {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
  background: #fff;
  border: 1px solid #f0e6d2;
  border-radius: 8px;
  padding: 8px 10px;
}
.approval-hint {
  margin-top: 8px;
  font-size: 13px;
  color: #b7791f;
}
/* Markdown 渲染区：HTML 结构由 marked 生成，取消 pre-wrap 以避免多余空行。 */
.markdown-body {
  white-space: normal;
  line-height: 1.7;
}
.markdown-body > *:first-child {
  margin-top: 0;
}
.markdown-body > *:last-child {
  margin-bottom: 0;
}
.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin: 14px 0 8px;
  font-weight: 600;
  color: #1d2129;
}
.markdown-body h1 { font-size: 18px; }
.markdown-body h2 { font-size: 16px; }
.markdown-body h3 { font-size: 15px; }
.markdown-body h4 { font-size: 14px; }
.markdown-body p {
  margin: 6px 0;
}
.markdown-body ul,
.markdown-body ol {
  margin: 6px 0;
  padding-left: 22px;
}
.markdown-body li {
  margin: 3px 0;
}
.markdown-body code {
  padding: 2px 5px;
  border-radius: 4px;
  background: #eef0f3;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  color: #d63200;
}
.markdown-body pre {
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #1d2129;
  overflow-x: auto;
}
.markdown-body pre code {
  padding: 0;
  background: none;
  color: #f2f3f5;
  font-size: 13px;
}
.markdown-body blockquote {
  margin: 8px 0;
  padding: 2px 12px;
  border-left: 3px solid #c9cdd4;
  color: #4e5969;
}
.markdown-body a {
  color: #3370ff;
  text-decoration: none;
}
.markdown-body a:hover {
  text-decoration: underline;
}
.markdown-body table {
  margin: 8px 0;
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  line-height: 1.6;
  border: 1px solid #e5e6eb;
  border-radius: 8px;
  overflow: hidden;
}
.markdown-body th,
.markdown-body td {
  padding: 7px 10px;
  border: 1px solid #e5e6eb;
  text-align: left;
  word-break: break-word;
}
.markdown-body th {
  background: #f2f5fb;
  font-weight: 600;
  color: #1d2129;
  white-space: nowrap;
}
.markdown-body tbody tr:nth-child(even) {
  background: #fafbfd;
}
.markdown-body tbody tr:hover {
  background: #f4f8ff;
}
.markdown-body img {
  max-width: 100%;
  border-radius: 6px;
}
.markdown-body hr {
  margin: 12px 0;
  border: none;
  border-top: 1px solid #e5e6eb;
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
.col-runs {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.runs-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.runs-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
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
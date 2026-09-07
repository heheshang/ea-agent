<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/http'
import { subscribeRun, type SseEventName, type SseSubscription } from '../api/sse'
import type { AgentChat, AgentRun, TemplateReview } from '../api/types'
import { renderMarkdown } from '../utils/markdown'
import { useAuthStore } from '../stores/auth'
import TemplateReviewPanel from '../components/TemplateReviewPanel.vue'

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
const chats = ref<AgentChat[]>([])
/** 当前聊天（V17）：新建聊天产出唯一 id + 描述；run 与调用链明细按 chat_id 追踪。 */
const activeChat = ref<AgentChat | null>(null)
const messages = ref<ChatMessage[]>([])
const thinkingOpen = ref<string[]>(['t'])
const chatBox = ref<HTMLDivElement | null>(null)
const auth = useAuthStore()
const loadingChat = ref(false)
const reviewOpen = ref(false)
const reviewTemplateId = ref<number>()
const latestReview = ref<TemplateReview | null>(null)
let es: SseSubscription | null = null
let revision = 0
let authRevision = 0
let runsRequest = 0
let chatsRequest = 0
let historyRequest = 0
let replaying = false

function invalidateRequests() {
  reviewOpen.value = false
  reviewTemplateId.value = undefined
  latestReview.value = null
  revision++
  es?.close()
  es = null
  running.value = false
  loadingChat.value = false
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant') last.streaming = false
}

/** 运行中无任何助手输出时的占位（首块到达前）。 */
const awaitingFirst = computed(() => running.value && messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'user')

const currentRunLabel = computed(() => (runId.value ? `Run #${runId.value} · ${runStatus.value}` : ''))

/** 聊天 chip：唯一 id + 描述（新建聊天/自动建聊天后展示）。 */
const currentChatLabel = computed(() =>
  activeChat.value ? `聊天 #${activeChat.value.id} · ${activeChat.value.description}` : '')

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
  const owner = authRevision
  ontologyLoading = get<{ nodes: OntologyNode[]; edges: OntologyEdge[] }>('/agent/stats/ontology-graph', { days: 30 })
    .then((g) => {
      if (owner !== authRevision) return
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
    .finally(() => { if (owner === authRevision) ontologyLoading = null })
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
    void refreshRuns()
    if (!replaying) void rebuildFromHistory()
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
      const result = parseToolResult(d.result)
      const template = parseToolResult(result?.result) ?? result
      if (template?.review_status === 'PENDING' && Number.isSafeInteger(Number(template.template_id)) && Number(template.template_id) > 0) {
        reviewTemplateId.value = Number(template.template_id)
        reviewOpen.value = true
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
  if (running.value || loadingChat.value) return
  const submittedGoal = goal.value.trim()
  if (!submittedGoal) {
    ElMessage.warning('请输入目标')
    return
  }
  invalidateRequests()
  const request = revision
  const chat = activeChat.value
  const submittedMode = mode.value
  replaying = false
  running.value = true
  thinkingOpen.value = ['t']
  messages.value.push({ role: 'user', text: submittedGoal, blocks: [] })
  try {
    await ensureOntology()
    if (request !== revision) return
    const r = await post<{ run_id: number; status: string; session_id: string; chat_id?: number; description?: string }>(
      '/agent/chat', { goal: submittedGoal, mode: submittedMode, chatId: chat?.id })
    if (request !== revision) return
    if (r.session_id) localStorage.setItem('ea.session_id', r.session_id)
    if (r.chat_id != null) {
      activeChat.value = {
        id: r.chat_id, tenantId: 0, userId: 0,
        description: r.description ?? chat?.description ?? '新对话',
        status: 'ACTIVE',
      }
      void loadChats()
    }
    runId.value = r.run_id
    runStatus.value = r.status
    es = subscribeRun(r.run_id, (name, data) => {
      if (request === revision) pushEvent(name, data)
    })
  } catch {
    if (request === revision) {
      running.value = false
      runStatus.value = 'ERROR'
    }
  }
}

function stop() {
  invalidateRequests()
}

async function replay(run: AgentRun) {
  invalidateRequests()
  const request = revision
  replaying = true
  activeChat.value = run.chatId == null ? null : chats.value.find(c => c.id === run.chatId) ?? {
    id: run.chatId, tenantId: run.tenantId, userId: run.userId,
    description: run.goal ?? '历史聊天', status: 'ACTIVE',
  }
  if (run.sessionId) localStorage.setItem('ea.session_id', run.sessionId)
  else localStorage.removeItem('ea.session_id')
  runId.value = run.id
  runStatus.value = run.status
  messages.value = [{ role: 'user', text: run.goal ?? '（历史 Run）', blocks: [] }]
  running.value = true
  thinkingOpen.value = ['t']
  await ensureOntology()
  if (request !== revision) return
  es = subscribeRun(run.id, (name, data) => {
    if (request === revision) pushEvent(name, data)
  })
}

/** 当前用户聊天列表（新到旧）。 */
async function loadChats() {
  const request = ++chatsRequest
  const owner = authRevision
  try {
    const result = await get<AgentChat[]>('/agent/chats', { limit: 50 })
    if (request === chatsRequest && owner === authRevision) chats.value = result
  } catch { /* HTTP interceptor displays the failure; retain the last list. */ }
}

/** 新建聊天：清除旧 session，首次发送时使用服务端返回的会话标识。 */
async function newChat() {
  if (running.value) return
  invalidateRequests()
  const request = revision
  loadingChat.value = true
  try {
    const r = await post<{ chat_id: number; description: string }>('/agent/chats', {})
    if (request !== revision) return
    activeChat.value = { id: r.chat_id, tenantId: 0, userId: 0, description: r.description, status: 'ACTIVE' }
    localStorage.removeItem('ea.session_id')
    replaying = false
    messages.value = []
    runs.value = []
    runId.value = null
    runStatus.value = ''
    void refreshRuns()
    void loadChats()
  } catch { /* HTTP interceptor displays the failure. */ }
  finally { if (request === revision) loadingChat.value = false }
}

/** 切换聊天：加载该聊天 runs（按 chat_id），以最新 run 的 session 续接（同一聊天内会话自洽），渲染为对话消息。 */
async function selectChat(c: AgentChat) {
  if (running.value) return
  invalidateRequests()
  const request = revision
  const listRequest = ++runsRequest
  replaying = false
  loadingChat.value = true
  activeChat.value = c
  localStorage.removeItem('ea.session_id')
  messages.value = []
  runs.value = []
  runId.value = null
  runStatus.value = ''
  try {
    const rs = await get<AgentRun[]>('/agent/runs', { limit: 200, chat_id: c.id })
    if (request !== revision) return
    if (listRequest === runsRequest) runs.value = rs
    const latest = rs[0]
    if (latest?.sessionId) localStorage.setItem('ea.session_id', latest.sessionId)
    messages.value = renderHistory([...rs].sort((a, b) => a.id - b.id))
  } catch { /* Keep the selected chat empty rather than showing another chat's history. */ }
  finally { if (request === revision) loadingChat.value = false }
}

async function refreshRuns() {
  const request = ++runsRequest
  const context = revision
  const params: Record<string, unknown> = { limit: 20 }
  if (activeChat.value) params.chat_id = activeChat.value.id
  try {
    const result = await get<AgentRun[]>('/agent/runs', params)
    if (request === runsRequest && context === revision) runs.value = result
  } catch { /* HTTP interceptor displays the failure; retain the last list. */ }
}

/** 历史按所选聊天或恢复的 session 限定，绝不混入其它聊天。 */
async function loadSessionHistory(): Promise<AgentRun[]> {
  const chatId = activeChat.value?.id
  const sessionId = localStorage.getItem('ea.session_id')
  if (chatId == null && !sessionId) return []
  const params = chatId == null ? { session_id: sessionId } : { chat_id: chatId }
  const rs = await get<AgentRun[]>('/agent/runs', { limit: 200, ...params })
  return rs.filter(r => chatId == null ? r.sessionId === sessionId : r.chatId === chatId).sort((a, b) => a.id - b.id)
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
  if (running.value || loadingChat.value || replaying) return
  const context = revision
  const request = ++historyRequest
  try {
    const history = await loadSessionHistory()
    if (context !== revision || request !== historyRequest || running.value) return
    const latest = history[history.length - 1]
    if (!activeChat.value && latest?.chatId != null) {
      activeChat.value = chats.value.find(c => c.id === latest.chatId) ?? {
        id: latest.chatId, tenantId: latest.tenantId, userId: latest.userId,
        description: latest.goal ?? '历史聊天', status: 'ACTIVE',
      }
      void refreshRuns()
    }
    messages.value = renderHistory(history)
  } catch { /* Preserve current messages on a failed history request. */ }
}

/** done 后：以最新 runs 重建消息 = 历史 + 本轮；本轮刚流式完成，保留完整 live 内容（思考/步骤/全文回复）不被 summary 摘要取代。 */
async function rebuildFromHistory() {
  const context = revision
  const request = ++historyRequest
  const completedRunId = runId.value
  const live = messages.value[messages.value.length - 1]
  try {
    const history = await loadSessionHistory()
    if (context !== revision || request !== historyRequest || !history.length) return
    const currentIndex = history.findIndex(r => r.id === completedRunId)
    if (currentIndex < 0) return
    const msgs = renderHistory(history)
    runStatus.value = history[currentIndex].status
    if (live?.role === 'assistant' && live.blocks.length) msgs[currentIndex * 2 + 1] = live
    messages.value = msgs
  } catch { /* Preserve the complete streamed response if history is unavailable. */ }
}

/** error 后：保留已流式内容，仅按后端最新状态刷新展示（如 SSE 断线但后端实际完成）。 */
async function refreshAfterError() {
  const context = revision
  const failedRunId = runId.value
  await refreshRuns()
  if (context !== revision) return
  const cur = runs.value.find(r => r.id === failedRunId)
  if (cur) runStatus.value = cur.status
}

function refreshWorkbench() {
  if (!auth.loggedIn) return
  if (!loadingChat.value) void refreshRuns()
  void loadChats()
  void loadSessionHistoryIntoMessages()
}

refreshWorkbench()
onActivated(refreshWorkbench)
watch(() => [auth.token, auth.tenantId], () => {
  invalidateRequests()
  authRevision++
  replaying = false
  activeChat.value = null
  messages.value = []
  runs.value = []
  chats.value = []
  runId.value = null
  runStatus.value = ''
  localStorage.removeItem('ea.session_id')
  ontologyLoaded = false
  ontologyLoading = null
  for (const cache of [actionObj, functionObj, objLabel]) {
    for (const key of Object.keys(cache)) delete cache[key]
  }
}, { flush: 'sync' })
onBeforeUnmount(() => {
  invalidateRequests()
  authRevision++
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

function parseToolResult(raw: unknown): Record<string, unknown> | null {
  if (typeof raw === 'string') {
    try { raw = JSON.parse(raw) } catch { return null }
  }
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : null
}

function useReviewFeedback() {
  const review = latestReview.value
  if (!review || running.value || loadingChat.value) return
  const decision = { COMMENT: '批注（未批准）', APPROVE: '通过', REJECT: '驳回' }[review.decision]
  goal.value = `模板 #${review.templateId}（审核版本 ${review.templateVersion}）人工反馈：${decision}。\n批注：${review.comment || '无'}\n请根据反馈提出下一步建议；如需修改，请生成新的待审模板，不要发送触达。`
  ElMessage.info('反馈已填入聊天框，确认后发送给助手')
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
    <header class="page-heading">
      <div>
        <p class="eyebrow">AI WORKSPACE</p>
        <h1>智能工作台</h1>
        <p class="page-description">用自然语言描述目标，让 AI 协助分析、规划与执行运营任务。</p>
      </div>
      <el-button :disabled="running" :loading="loadingChat" @click="newChat()">新建聊天</el-button>
    </header>

    <div class="wb-toolbar">
      <div class="mode-control">
        <span class="toolbar-label">执行模式</span>
        <el-radio-group v-model="mode" aria-label="执行模式">
          <el-radio-button value="auto">直接执行</el-radio-button>
          <el-radio-button value="suggest">建议确认</el-radio-button>
        </el-radio-group>
      </div>
      <p class="mode-hint">{{ mode === 'auto' ? '助手将直接执行任务中的操作' : '涉及写入的操作，先生成建议，确认后执行' }}</p>
    </div>

    <div class="wb-grid">
      <section class="col-chat" aria-label="智能助手对话">
        <div class="chat-card">
          <div class="chat-header">
            <div class="chat-heading">
              <span class="presence-dot" :class="{ busy: running }" aria-hidden="true"></span>
              <span class="chat-title">{{ activeChat?.description || '新的对话' }}</span>
            </div>
            <span v-if="currentRunLabel" class="run-chip">{{ currentRunLabel }}</span>
            <span v-else class="session-hint">{{ loadingChat ? '正在加载' : 'AI 运营助手' }}</span>
          </div>

          <div v-if="messages.length === 0" class="chat-empty">
            <div class="empty-mark" aria-hidden="true">AI<span></span></div>
            <p class="empty-eyebrow">从一个目标开始</p>
            <h2>今天，有什么可以帮你？</h2>
            <p class="empty-description">查询业务数据、梳理运营思路，或交给助手执行任务。<br />思考过程、操作步骤与结果将在这里清晰呈现。</p>
            <div class="suggestions" aria-label="目标建议">
              <button
                v-for="suggestion in ['查询最近活跃的客户', '分析近期客户互动情况', '查询最近活跃客户并发送优惠']"
                :key="suggestion"
                type="button"
                :disabled="running || loadingChat"
                @click="goal = suggestion"
              >{{ suggestion }}<span aria-hidden="true">↗</span></button>
            </div>
            <span class="suggestion-hint">选择一个示例，编辑后发送</span>
          </div>
          <div v-else ref="chatBox" class="chat-box" aria-label="对话消息">
            <div v-for="(m, mi) in messages" :key="mi" class="msg-row" :class="m.role">
              <div v-if="m.role === 'user'" class="bubble user">{{ m.text }}</div>
              <div v-else class="row-inner">
                <span class="a-avatar" aria-label="AI 助手">AI</span>
                <div class="bubble assistant">
                  <template v-for="(b, bi) in m.blocks" :key="bi">
                    <el-collapse v-if="b.kind === 'thinking' && b.text" v-model="thinkingOpen" class="thinking">
                      <el-collapse-item :title="`思考过程 · ${b.text.length} 字`" name="t">
                        <p class="thinking-text">{{ b.text }}</p>
                      </el-collapse-item>
                    </el-collapse>
                    <div v-else-if="b.kind === 'step'" class="step">
                      <div class="step-title">{{ b.title }}</div>
                      <pre v-if="b.detail != null">{{ pretty(b.detail) }}</pre>
                    </div>
                    <div v-else-if="b.kind === 'approval'" class="approval-card">
                      <div class="approval-title">等待确认 · {{ b.action }}</div>
                      <pre v-if="b.args != null" class="approval-args">{{ pretty(b.args) }}</pre>
                      <div class="approval-hint">请在聊天中回复「确认执行」或「取消」，由助手放行后执行</div>
                    </div>
                    <p v-else-if="b.kind === 'reply' && m.streaming" class="reply">{{ b.text }}</p>
                    <div v-else-if="b.kind === 'reply'" class="reply markdown-body" v-html="renderMarkdown(b.text)" />
                  </template>
                </div>
              </div>
            </div>
            <div v-if="awaitingFirst" class="msg-row assistant" role="status" aria-label="助手正在思考">
              <div class="row-inner">
                <span class="a-avatar">AI</span>
                <div class="bubble assistant typing">
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                  <span class="typing-label">正在思考</span>
                </div>
              </div>
            </div>
          </div>

          <div class="template-review-entry">
            <el-button :disabled="loadingChat" :aria-expanded="reviewOpen" @click="reviewOpen = !reviewOpen">{{ reviewOpen ? '收起模板审核' : '模板批注 / HITL 审核' }}</el-button>
            <span>人工批注与审批，不自动执行发送</span>
          </div>
          <template v-if="reviewOpen">
            <TemplateReviewPanel v-if="activeChat" :key="`${auth.tenantId}:${auth.token}:${activeChat.id}`" :chat-id="activeChat.id" :template-id="reviewTemplateId" :disabled="running || loadingChat" @reviewed="latestReview = $event" />
            <div v-else class="template-review-entry">
              <span>先新建或选择聊天，批注将关联当前聊天并持久保存。</span>
              <el-button :disabled="running || loadingChat" @click="newChat()">新建聊天</el-button>
            </div>
          </template>
          <div v-if="latestReview" class="template-review-entry" role="status">
            <span>模板 #{{ latestReview.templateId }} 的人工反馈已保存</span>
            <el-button :disabled="running || loadingChat" @click="useReviewFeedback">将反馈带入聊天</el-button>
          </div>
          <div class="input-card">
            <el-input
              v-model="goal"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 5 }"
              resize="none"
              aria-label="任务目标"
              placeholder="描述你的目标，例如：查询最近活跃的客户并发送优惠"
              :disabled="running || loadingChat"
              @keydown.enter.exact="!$event.isComposing && ($event.preventDefault(), running ? stop() : start())"
            />
            <div class="input-footer">
              <span class="input-hint">{{ running ? '任务进行中，可随时停止' : 'Enter 发送 · Shift + Enter 换行' }}</span>
              <el-button class="send-btn" :type="running ? 'danger' : 'primary'" :disabled="loadingChat" @click="running ? stop() : start()">
                {{ running ? '停止生成' : '发送目标' }}
              </el-button>
            </div>
          </div>
        </div>
        <p v-if="currentChatLabel" class="chat-context">{{ currentChatLabel }}</p>
      </section>

      <aside class="col-runs" aria-label="聊天与执行历史">
        <section class="history-card">
          <div class="history-header"><h2>最近聊天</h2><span class="count-badge">{{ chats.length }}</span></div>
          <div class="history-list chats-list">
            <div v-if="chats.length === 0" class="no-runs"><span class="empty-list-mark" aria-hidden="true">—</span><p>还没有聊天记录</p><span>开始对话后，在这里继续查看</span></div>
            <button
              v-for="c in chats"
              :key="c.id"
              type="button"
              class="chat-item"
              :class="{ active: activeChat?.id === c.id }"
              :aria-pressed="activeChat?.id === c.id"
              :disabled="running"
              @click="selectChat(c)"
            >
              <span class="chat-desc">{{ c.description }}</span>
              <span class="item-meta"><span>聊天 #{{ c.id }}</span><span v-if="activeChat?.id === c.id">当前对话</span></span>
            </button>
          </div>
        </section>
        <section class="history-card">
          <div class="history-header"><h2>执行历史</h2><span class="count-badge">{{ runs.length }}</span></div>
          <div class="history-list runs-list">
            <div v-if="runs.length === 0" class="no-runs"><span class="empty-list-mark" aria-hidden="true">—</span><p>暂无执行记录</p><span>任务的执行过程将在这里留存</span></div>
            <button v-for="r in runs" :key="r.id" type="button" class="run-item" :class="{ active: runId === r.id }" :aria-pressed="runId === r.id" @click="replay(r)">
              <span class="run-line">
                <span class="run-id">#{{ r.id }}</span>
                <el-tag size="small" effect="light" :type="r.status === 'COMPLETED' ? 'success' : r.status === 'FAILED' ? 'danger' : 'info'">{{ r.status }}</el-tag>
              </span>
              <span class="run-goal">{{ r.goal }}</span>
              <span class="run-time">{{ r.createdAt }}</span>
            </button>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.template-review-entry { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 14px 22px; border-top: 1px solid var(--ea-border); }
.template-review-entry > span { color: var(--ea-text-muted); font-size: 12px; }
.wb {
  max-width: 1440px;
  min-width: 0;
  margin: 0 auto;
  color: #172b45;
}
.page-heading, .wb-toolbar, .mode-control, .chat-header, .chat-heading, .input-footer, .history-header, .run-line, .item-meta {
  display: flex;
  align-items: center;
}
.page-heading { justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.eyebrow { margin: 0 0 8px; color: var(--db-primary, #3864ed); font-size: 10px; font-weight: 700; letter-spacing: 1.8px; }
.page-heading h1 { margin: 0; font-size: 26px; line-height: 1.35; font-weight: 650; letter-spacing: -0.6px; }
.page-description { margin: 9px 0 0; color: var(--ea-text-muted, #69798f); font-size: 13px; line-height: 1.8; }
.page-heading > .el-button { flex-shrink: 0; }
.wb-toolbar { gap: 20px; flex-wrap: wrap; margin-bottom: 18px; padding: 14px 18px; background: #fff; border: 1px solid var(--ea-border, #e4eaf2); border-radius: 12px; }
.mode-control { gap: 14px; }
.toolbar-label { font-size: 12px; font-weight: 600; white-space: nowrap; }
.mode-control :deep(.el-radio-button__inner) { padding: 9px 15px; font-size: 12px; }
.mode-hint { margin: 0; color: var(--ea-text-muted, #69798f); font-size: 12px; line-height: 1.7; }
.wb-grid { display: grid; grid-template-columns: minmax(0, 1fr) 296px; gap: 20px; align-items: start; }
.col-chat, .col-runs { min-width: 0; }
.chat-card { min-width: 0; background: #fff; border: 1px solid var(--ea-border, #e4eaf2); border-radius: 16px; overflow: hidden; }
.chat-header { min-width: 0; min-height: 62px; justify-content: space-between; gap: 12px; padding: 14px 22px; border-bottom: 1px solid #eef1f6; }
.chat-heading { min-width: 0; gap: 9px; }
.presence-dot { flex: none; width: 7px; height: 7px; background: #47a48a; border-radius: 50%; }
.presence-dot.busy { background: var(--db-primary, #3864ed); }
.chat-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; }
.session-hint { color: var(--ea-text-muted, #69798f); font-size: 11px; white-space: nowrap; }
.run-chip { flex-shrink: 0; padding: 4px 8px; border-radius: 6px; background: #edf2ff; color: #3864ed; font-size: 10px; overflow-wrap: anywhere; }
.chat-empty { min-height: 418px; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 36px 24px; text-align: center; }
.empty-mark { position: relative; display: grid; place-items: center; width: 62px; height: 62px; margin-bottom: 23px; background: linear-gradient(145deg, #4e77f5, #3054ce); border: 1px solid #557df1; border-radius: 19px; box-shadow: 0 8px 20px #3864ed1a; color: #fff; font-size: 23px; font-weight: 650; letter-spacing: -1px; }
.empty-mark > span { position: absolute; right: -4px; top: -4px; width: 13px; height: 13px; border: 3px solid #fff; border-radius: 50%; background: #8cb4ff; }
.empty-eyebrow { margin: 0 0 9px; font-size: 11px; letter-spacing: 2px; color: var(--ea-text-muted, #69798f); }
.chat-empty h2 { margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.5px; }
.empty-description { margin: 14px 0 22px; font-size: 12px; line-height: 1.9; color: var(--ea-text-muted, #69798f); }
.suggestions { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; max-width: 550px; }
.suggestions button { display: flex; align-items: center; gap: 16px; padding: 10px 12px; border: 1px solid #e4eaf2; border-radius: 9px; background: #fff; color: #53657e; font: inherit; font-size: 12px; text-align: left; cursor: pointer; transition: border-color 0.15s, background 0.15s; }
.suggestions button > span { margin-left: auto; color: #8b9bb1; }
.suggestions button:hover:not(:disabled) { background: #f7f9ff; border-color: #b5c6fc; }
.suggestion-hint { margin-top: 14px; font-size: 10px; color: #8290a4; }
.chat-box { min-height: 418px; max-height: 560px; overflow-y: auto; overscroll-behavior: contain; padding: 24px 22px 8px; }
.msg-row { display: flex; min-width: 0; margin-bottom: 22px; }
.msg-row.user { justify-content: flex-end; }
.row-inner { display: flex; gap: 10px; align-items: flex-start; width: 100%; min-width: 0; }
.a-avatar { display: grid; place-items: center; flex: none; width: 30px; height: 30px; border-radius: 9px; background: #edf2ff; color: #3864ed; font-size: 11px; font-weight: 700; }
.bubble { min-width: 0; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.bubble.user { max-width: 86%; padding: 11px 16px; border-radius: 13px 13px 4px 13px; background: #edf2ff; color: #254eaa; white-space: pre-wrap; }
.bubble.assistant { flex: 1; padding: 4px 0; }
.thinking { margin-bottom: 12px; border: 1px solid #e8edf5; border-radius: 9px; overflow: hidden; }
.thinking :deep(.el-collapse-item__header) { height: auto; min-height: 36px; padding: 0 12px; background: #f8fafd; color: #69798f; font-size: 11px; line-height: 1.6; }
.thinking :deep(.el-collapse-item__wrap) { border-bottom: none; }
.thinking :deep(.el-collapse-item__content) { padding-bottom: 0; }
.thinking-text { max-height: 260px; overflow-y: auto; margin: 0; padding: 12px; color: #69798f; font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.step { margin-bottom: 12px; }
.step-title { margin-bottom: 6px; color: #536c9b; font-size: 11px; font-weight: 600; overflow-wrap: anywhere; }
.step pre, .approval-args { max-height: 200px; overflow: auto; margin: 0; padding: 10px 12px; border: 1px solid #e8edf5; border-radius: 8px; background: #f8fafd; color: #627087; font-size: 11px; line-height: 1.6; }
.reply { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.approval-card { margin: 12px 0; padding: 14px; border: 1px solid #efdfbd; border-radius: 10px; background: #fffbf3; }
.approval-title { margin-bottom: 8px; color: #96651c; font-size: 13px; font-weight: 600; }
.approval-args { background: #fff; border-color: #eee5d4; white-space: pre-wrap; overflow-wrap: anywhere; }
.approval-hint { margin-top: 10px; color: #96651c; font-size: 12px; }
.markdown-body { white-space: normal; line-height: 1.8; }
.markdown-body :deep(> :first-child) { margin-top: 0; }
.markdown-body :deep(> :last-child) { margin-bottom: 0; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3), .markdown-body :deep(h4) { margin: 16px 0 8px; color: #172b45; font-weight: 600; line-height: 1.5; }
.markdown-body :deep(h1) { font-size: 20px; }
.markdown-body :deep(h2) { font-size: 18px; }
.markdown-body :deep(h3) { font-size: 16px; }
.markdown-body :deep(h4) { font-size: 14px; }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { margin: 8px 0; padding-left: 22px; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(code) { padding: 2px 5px; border-radius: 4px; background: #eef2f7; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; color: #b24e4e; }
.markdown-body :deep(pre) { margin: 10px 0; max-width: 100%; padding: 12px 14px; overflow-x: auto; border-radius: 9px; background: #172b45; }
.markdown-body :deep(pre code) { padding: 0; background: none; color: #e5ebf5; }
.markdown-body :deep(blockquote) { margin: 10px 0; padding: 2px 12px; border-left: 3px solid #c1d0f3; color: #69798f; }
.markdown-body :deep(a) { color: #3864ed; text-decoration: underline; text-underline-offset: 3px; }
.markdown-body :deep(table) { display: block; max-width: 100%; overflow-x: auto; margin: 12px 0; border-collapse: collapse; font-size: 12px; }
.markdown-body :deep(th), .markdown-body :deep(td) { padding: 8px 10px; border: 1px solid #e4eaf2; text-align: left; }
.markdown-body :deep(th) { background: #f3f6fa; font-weight: 600; }
.markdown-body :deep(img) { max-width: 100%; height: auto; border-radius: 8px; }
.markdown-body :deep(hr) { margin: 16px 0; border: 0; border-top: 1px solid #e4eaf2; }
.bubble.typing { display: flex; align-items: center; gap: 5px; min-height: 30px; }
.typing .dot { width: 5px; height: 5px; border-radius: 50%; background: #3864ed; animation: blink 1.2s infinite; }
.typing .dot:nth-child(2) { animation-delay: 0.2s; }
.typing .dot:nth-child(3) { animation-delay: 0.4s; }
.typing-label { margin-left: 5px; color: #69798f; font-size: 11px; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.3; } 40% { opacity: 1; } }
.input-card { margin: 0 18px 18px; padding: 12px 14px; border: 1px solid #dce4f0; border-radius: 12px; background: #fff; transition: border-color 0.15s, box-shadow 0.15s; }
.input-card:focus-within { border-color: #a3b8fa; box-shadow: 0 0 0 3px #3864ed08; }
.input-card :deep(.el-textarea__inner) { padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; color: #172b45; font-size: 13px; line-height: 1.8; }
.input-card :deep(.el-textarea.is-disabled .el-textarea__inner) { color: #8795a8; background: transparent; }
.input-footer { justify-content: space-between; gap: 12px; margin-top: 12px; }
.input-hint { color: #8290a4; font-size: 10px; line-height: 1.6; }
.send-btn { flex-shrink: 0; border-radius: 8px; font-size: 12px; }
.chat-context { margin: 10px 4px 0; color: #8290a4; font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.col-runs { display: flex; flex-direction: column; gap: 18px; }
.history-card { min-width: 0; overflow: hidden; border: 1px solid var(--ea-border, #e4eaf2); border-radius: 12px; background: #fff; }
.history-header { justify-content: space-between; gap: 12px; padding: 17px 18px; border-bottom: 1px solid #eef1f6; }
.history-header h2 { margin: 0; font-size: 13px; font-weight: 600; }
.count-badge { min-width: 22px; padding: 2px 6px; border-radius: 5px; background: #f1f4f9; color: #8290a4; font-size: 10px; text-align: center; }
.history-list { overflow-y: auto; padding: 8px; }
.chats-list { max-height: 272px; }
.runs-list { max-height: 360px; }
.chat-item, .run-item { display: block; width: 100%; min-width: 0; padding: 12px 10px; border: 1px solid transparent; border-radius: 8px; background: #fff; color: inherit; font: inherit; text-align: left; cursor: pointer; transition: background 0.15s, border-color 0.15s; }
.chat-item + .chat-item, .run-item + .run-item { margin-top: 3px; }
.chat-item:hover:not(:disabled), .run-item:hover { background: #f6f8fc; }
.chat-item.active, .run-item.active { border-color: #dce5ff; background: #f0f4ff; }
.chat-item:disabled, .suggestions button:disabled { cursor: not-allowed; opacity: 0.6; }
.chat-item:focus-visible, .run-item:focus-visible, .suggestions button:focus-visible { outline: 2px solid #3864ed; outline-offset: -2px; }
.chat-desc { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 500; line-height: 1.7; }
.item-meta { justify-content: space-between; gap: 6px; margin-top: 7px; color: #8290a4; font-size: 10px; }
.chat-item.active .chat-desc, .chat-item.active .item-meta { color: #3864ed; }
.run-line { justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.run-line :deep(.el-tag) { max-width: 100%; height: auto; min-height: 20px; padding: 2px 6px; font-size: 9px; white-space: normal; overflow-wrap: anywhere; }
.run-id { color: #69798f; font-size: 11px; font-weight: 600; }
.run-goal { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-top: 9px; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.run-time { display: block; margin-top: 7px; color: #8290a4; font-size: 10px; overflow-wrap: anywhere; }
.no-runs { padding: 24px 8px 28px; text-align: center; color: #8290a4; font-size: 10px; line-height: 1.7; }
.empty-list-mark { display: grid; place-items: center; width: 30px; height: 30px; margin: 0 auto 10px; border: 1px solid #e4eaf2; border-radius: 9px; color: #a1aec1; }
.no-runs p { margin: 0 0 4px; color: #69798f; font-size: 12px; }
@media (max-width: 1100px) {
  .wb-grid { grid-template-columns: minmax(0, 1fr) 270px; gap: 16px; }
  .wb-toolbar { gap: 10px 20px; }
}
@media (max-width: 960px) {
  .wb-grid { grid-template-columns: minmax(0, 1fr); }
  .col-runs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
  .history-card { align-self: start; }
}
@media (max-width: 600px) {
  .page-heading { align-items: flex-start; flex-wrap: wrap; gap: 14px; margin-bottom: 18px; }
  .page-heading h1 { font-size: 23px; }
  .page-description { font-size: 12px; }
  .wb-toolbar { padding: 14px; gap: 10px; }
  .mode-control { width: 100%; justify-content: space-between; gap: 10px; }
  .mode-control :deep(.el-radio-button__inner) { padding: 9px 12px; }
  .mode-hint { font-size: 11px; }
  .chat-header { flex-wrap: wrap; min-height: 56px; padding: 14px; }
  .chat-heading { flex: 1; }
  .chat-empty { min-height: 390px; padding: 30px 16px; }
  .chat-empty h2 { font-size: 20px; }
  .empty-description { font-size: 11px; }
  .suggestions { width: 100%; }
  .suggestions button { width: 100%; padding: 10px 12px; }
  .chat-box { min-height: 360px; max-height: 480px; padding: 18px 12px 0; }
  .row-inner { gap: 8px; }
  .a-avatar { width: 25px; height: 25px; font-size: 10px; border-radius: 7px; }
  .bubble { font-size: 12px; }
  .bubble.user { max-width: 90%; }
  .input-card { margin: 0 10px 10px; padding: 12px; }
  .input-footer { gap: 8px; }
  .input-hint { max-width: 155px; }
  .col-runs { grid-template-columns: minmax(0, 1fr); }
}
@media (prefers-reduced-motion: reduce) {
  .typing .dot { animation: none; }
}
</style>
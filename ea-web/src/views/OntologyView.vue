<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { get } from '../api/http'

/**
 * Ontology 调用链路页：流程图（引擎 → 工具 → Action/Function 分支 → 对象）+ 运行时调用热点。
 * 节点/边为代码事实（TypeRegistry 7 对象 / ActionRegistry 6 Action / FunctionRegistry 5 Function / AgentToolRegistry 7 工具），
 * 运行时统计来自 agent_run.tool_calls（calls / avg_ms / fails 徽章）。
 * 未调用过的节点与静态边置灰虚线展示——图同时是「Ontology 架构图」与「调用热点图」。
 */
interface OntologyNode {
  id: string
  type: 'engine' | 'tool' | 'action' | 'function' | 'object'
  label: string
  calls?: number
  avg_ms?: number
  fails?: number
  count?: number // 仅 object 节点：对象数据量
  fields?: number // 仅 object 节点：TypeRegistry 定义字段数
}
interface OntologyEdge {
  from: string
  to: string
  calls?: number
  avg_ms?: number
  fails?: number
}
interface OntologyGraphData {
  nodes: OntologyNode[]
  edges: OntologyEdge[]
}

const days = ref(30)
const loading = ref(false)
const data = ref<OntologyGraphData | null>(null)

function nodeById(id: string): OntologyNode | undefined {
  return data.value?.nodes.find((n) => n.id === id)
}

function layerNodes(type: OntologyNode['type']): OntologyNode[] {
  return (data.value?.nodes ?? []).filter((n) => n.type === type)
}

function fmtInt(v: number | undefined | null): string {
  if (v === undefined || v === null) return ''
  return new Intl.NumberFormat('en-US').format(v)
}

/** 流程图布局：5 条纵向泳道（引擎/工具/Action/Function/对象），SVG 正交折线 + 箭头 */
interface FlowNode {
  id: string
  kind: OntologyNode['type']
  x: number
  y: number
  w: number
  h: number
}
interface FlowEdge {
  from: string
  to: string
  d: string
  calls?: number
  avg_ms?: number
  dead: boolean
  showLabel: boolean
  lx: number
  ly: number
}
const FLOW_KINDS: OntologyNode['type'][] = ['engine', 'tool', 'action', 'function', 'object']
const COL_W: Record<OntologyNode['type'], number> = { engine: 120, tool: 198, action: 198, function: 198, object: 200 }
const BOX_W: Record<OntologyNode['type'], number> = { engine: 112, tool: 190, action: 190, function: 190, object: 186 }
const BOX_H: Record<OntologyNode['type'], number> = { engine: 44, tool: 54, action: 54, function: 54, object: 62 }
const ROW_H = 78
const PAD_T = 44

const flow = computed(() => {
  let x = 24
  const colX: Partial<Record<OntologyNode['type'], number>> = {}
  for (const k of FLOW_KINDS) {
    colX[k] = x
    x += COL_W[k] + 30
  }
  // 节点 div 实际渲染高度（端口均布以可见盒为准，避免多出端口溢出盒下沿）
  const PORT_H: Record<OntologyNode['type'], number> = { engine: 48, tool: 36, action: 36, function: 36, object: 56 }
  const nodes: FlowNode[] = []
  const pos = new Map<string, { cx: number; cy: number; w: number; kind: OntologyNode['type']; y: number }>()
  let rows = 1
  for (const k of FLOW_KINDS) {
    const list = layerNodes(k)
    rows = Math.max(rows, list.length)
    list.forEach((n, i) => {
      const x0 = colX[k]!
      const y0 = PAD_T + i * ROW_H
      nodes.push({ id: n.id, kind: k, x: x0, y: y0, w: BOX_W[k], h: BOX_H[k] })
      pos.set(n.id, { cx: x0 + BOX_W[k] / 2, cy: y0 + BOX_H[k] / 2, w: BOX_W[k], kind: k, y: y0 })
    })
  }
  // —— 连线路由（正交折线，不压任何盒子）——
  // 竖线段走「列间隙」：相邻泳道之间 38px 空隙（x ∈ 前泳道右缘..后泳道左缘），垂直转弯都在空隙内；
  // 横线段走「行间隙」：上下两行盒子之间 16px 空隙带，跨泳道长边在行间隙内横穿，不会压在盒子上；
  // 同一节点的多条出/入边按端口垂直均布（箭头不会汇聚同一点），同间隙多条竖线 x 错开。
  const gaps = FLOW_KINDS.slice(0, -1).map((k, i) => {
    const next = FLOW_KINDS[i + 1]!
    return { left: colX[k]! + BOX_W[k], right: colX[next]!, center: (colX[k]! + BOX_W[k] + colX[next]!) / 2 }
  })
  // 出/入端口数：每条边在源盒右缘 / 目标盒左缘取独立端口 y
  const outCnt = new Map<string, number>()
  const inCnt = new Map<string, number>()
  const edges0 = (data.value?.edges ?? []).filter((e) => pos.has(e.from) && pos.has(e.to))
  for (const e of edges0) {
    outCnt.set(e.from, (outCnt.get(e.from) ?? 0) + 1)
    inCnt.set(e.to, (inCnt.get(e.to) ?? 0) + 1)
  }
  const outUsed = new Map<string, number>()
  const inUsed = new Map<string, number>()
  // 每个列间隙的竖线 x 均布
  const gapTotal = new Map<number, number>()
  const gapUsed = new Map<number, number>()
  for (const e of edges0) {
    const li = FLOW_KINDS.indexOf(pos.get(e.from)!.kind)
    const ri = FLOW_KINDS.indexOf(pos.get(e.to)!.kind)
    for (let gi = li; gi < ri; gi++) gapTotal.set(gi, (gapTotal.get(gi) ?? 0) + 1)
  }
  function gapX(gi: number): number {
    const g = gaps[gi]!
    const n = gapTotal.get(gi)!
    const i = gapUsed.get(gi) ?? 0
    gapUsed.set(gi, i + 1)
    const step = Math.min(6, (g.right - g.left - 10) / Math.max(1, n - 1))
    const x = g.center + (i - (n - 1) / 2) * step
    return Math.max(g.left + 4, Math.min(g.right - 4, x))
  }
  const edges: FlowEdge[] = edges0.map((e) => {
    const f = pos.get(e.from)!
    const t = pos.get(e.to)!
    const li = FLOW_KINDS.indexOf(f.kind)
    const ri = FLOW_KINDS.indexOf(t.kind)
    const sx = f.cx + f.w / 2
    const ex = t.cx - t.w / 2
    const oc = outCnt.get(e.from)!
    const ic = inCnt.get(e.to)!
    const oi = outUsed.get(e.from) ?? 0
    const ii = inUsed.get(e.to) ?? 0
    outUsed.set(e.from, oi + 1)
    inUsed.set(e.to, ii + 1)
    // 端口垂直均布，间距 ≤ 12px（盒内上下留白 ≥ 8px），以可见盒高/盒顶为基准
    const stepOut = Math.min(12, (PORT_H[f.kind] - 20) / Math.max(1, oc - 1))
    const stepIn = Math.min(12, (PORT_H[t.kind] - 20) / Math.max(1, ic - 1))
    const sy = f.y + PORT_H[f.kind] / 2 + (oi - (oc - 1) / 2) * stepOut
    const ey = t.y + PORT_H[t.kind] / 2 + (ii - (ic - 1) / 2) * stepIn
    // 公共行间隙 y：横穿段取离两端端口最近的行间隙带中点（object 盒最高 62px → 间隙 16px 在盒底之上 8px）
    const rw = Math.max(0, Math.min(rows - 1, Math.round(((sy + ey) / 2 - PAD_T) / ROW_H)))
    const yw = PAD_T + rw * ROW_H + BOX_H.object + (ROW_H - BOX_H.object) / 2
    let d: string
    let runW = 0
    let labelX = 0
    if (li === ri - 1) {
      // 相邻泳道：单间隙内 90° 拐弯
      const xk = gapX(li)
      d =
        sy === ey
          ? `M ${sx} ${sy} L ${xk} ${sy} L ${ex} ${ey}`
          : `M ${sx} ${sy} L ${xk} ${sy} L ${xk} ${ey} L ${ex} ${ey}`
    } else {
      // 跨泳道：出 → 列间隙竖移 → 行间隙横穿 → 列间隙竖移 → 入
      const x1 = gapX(li)
      const xk = gapX(ri - 1)
      d = `M ${sx} ${sy} L ${x1} ${sy} L ${x1} ${yw} L ${xk} ${yw} L ${xk} ${ey} L ${ex} ${ey}`
      runW = xk - x1
      labelX = (x1 + xk) / 2
    }
    return {
      from: e.from,
      to: e.to,
      d,
      calls: e.calls,
      avg_ms: e.avg_ms,
      dead: !e.calls,
      // 相邻泳道短边与节点徽章重复 → 仅跨泳道长链边标调用数；标签落在行间隙带内，不压盒；对象边只做线型不标数
      showLabel: !!e.calls && runW >= 80 && !e.to.startsWith('obj:'),
      lx: labelX,
      ly: yw + 4,
    }
  })
  const cols = FLOW_KINDS.map((k) => ({
    x: colX[k]!,
    label: k === 'engine' ? '引擎' : k === 'tool' ? '工具' : k === 'action' ? 'Action' : k === 'function' ? 'Function' : '对象',
  }))
  return { w: x - 30 + 24, h: PAD_T + rows * ROW_H + 24, nodes, edges, cols }
})

async function load() {
  loading.value = true
  try {
    data.value = await get<OntologyGraphData>('/agent/stats/ontology-graph', { days: days.value })
  } finally {
    loading.value = false
  }
}

/** 对象数据下钻弹窗（对象节点 / 总览表「数据」按钮共用） */
interface DrillState {
  type: string // 去掉 obj: 前缀的对象类型，如 customer
  label: string
  count: number
  fields: number
}
interface ObjRow {
  [key: string]: unknown
}
interface PageResult<T> {
  items: T[]
  nextPageToken: string | null
  total: number
}

const router = useRouter()
const drillVisible = ref(false)
const drillState = ref<DrillState | null>(null)
const drillLoading = ref(false)
const drillItems = ref<ObjRow[]>([])
const drillColumns = ref<string[]>([])
const drillTotal = ref(0)
const drillNextToken = ref<string | null>(null)

function openDrill(node: OntologyNode) {
  drillState.value = {
    type: node.id.replace(/^obj:/, ''),
    label: node.label,
    count: node.count ?? 0,
    fields: node.fields ?? 0,
  }
  drillItems.value = []
  drillColumns.value = []
  drillTotal.value = 0
  drillNextToken.value = null
  drillVisible.value = true
  loadDrill(true)
}

async function loadDrill(reset: boolean) {
  const st = drillState.value
  if (!st || !drillVisible.value) return
  drillLoading.value = true
  try {
    const params: Record<string, unknown> = { limit: 20, sort: '-created_at' }
    if (!reset && drillNextToken.value) params.page_token = drillNextToken.value
    const page = await get<PageResult<ObjRow>>(`/objects/${st.type}`, params)
    if (reset) {
      drillItems.value = page.items
      drillColumns.value = page.items.length ? Object.keys(page.items[0]) : []
    } else {
      drillItems.value = [...drillItems.value, ...page.items]
    }
    drillTotal.value = page.total
    drillNextToken.value = page.nextPageToken
  } finally {
    drillLoading.value = false
  }
}

function loadMore() {
  if (drillLoading.value) return
  loadDrill(false)
}

function jumpTo(node: OntologyNode) {
  if (node.id === 'obj:customer') router.push('/customers')
  else if (node.id === 'obj:campaign') router.push('/campaigns')
}

/** —— 调用链回放（/agent/stats/run-trace → 流程图高亮动效）—— */
interface TraceCall {
  seq: number
  kind: string
  name: string
  target?: string | null
  args?: string | null
  duration_ms?: number | null
  ok?: boolean
  error?: string | null
}
interface RunItem {
  id: number
  createdAt?: string
  status?: string
  summary?: string
}
const runs = ref<RunItem[]>([])
const runsLoading = ref(false)
const runId = ref<number | null>(null)
const trace = ref<TraceCall[]>([])
/** 当前播放步（-1 = 未开始；0 起每步推进） */
const step = ref(-1)
const playing = ref(false)
const speed = ref(1)
let traceTimer: number | undefined

async function loadRuns() {
  runsLoading.value = true
  try {
    runs.value = (await get<RunItem[]>('/agent/runs', { limit: 30 })) ?? []
  } finally {
    runsLoading.value = false
  }
}

function runTime(r: RunItem): string {
  if (!r.createdAt) return ''
  const t = new Date(r.createdAt)
  if (isNaN(t.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(t.getMonth() + 1)}-${p(t.getDate())} ${p(t.getHours())}:${p(t.getMinutes())}`
}

function runLabel(r: RunItem): string {
  const s = (r.summary ?? '').replace(/\s+/g, ' ').trim()
  const brief = s.length > 22 ? s.slice(0, 22) + '…' : s
  return `#${r.id} · ${runTime(r)} · ${brief}`
}

async function onRunChange(id: number) {
  playing.value = false
  step.value = -1
  trace.value = []
  if (!id) return
  try {
    const res = await get<{ run: RunItem; trace: TraceCall[] }>('/agent/stats/run-trace', { run_id: id })
    trace.value = res?.trace ?? []
    if (!trace.value.length) return
    step.value = 0
    playing.value = true
  } catch {
    trace.value = []
  }
}

/** 单步调用的节点 id 集合（工具 + 动作/函数目标） */
function stepNodes(c: TraceCall): string[] {
  const ids = [`tool:${c.name}`]
  if (c.target) ids.push(`${c.kind}:${c.target}`)
  return ids
}

/** 单步调用的边（engine→tool、tool→action/function，动作/函数→对象静态边存在则一并点亮） */
function stepEdges(c: TraceCall): [string, string][] {
  const es: [string, string][] = [['engine', `tool:${c.name}`]]
  if (c.target) {
    const t = `${c.kind}:${c.target}`
    es.push([`tool:${c.name}`, t])
    const oe = data.value?.edges.find((x) => x.from === t && x.to.startsWith('obj:'))
    if (oe) es.push([t, oe.to])
  }
  return es
}

/** 已走过的边 key（from|to） */
const traceEdges = computed(() => {
  const s = new Set<string>()
  for (let i = 0; i <= step.value && i < trace.value.length; i++) {
    for (const [a, b] of stepEdges(trace.value[i]!)) s.add(`${a}|${b}`)
  }
  return s
})

/** 已走过的节点 id */
const traceVisitedNodes = computed(() => {
  const s = new Set<string>()
  for (let i = 0; i <= step.value && i < trace.value.length; i++) {
    for (const id of stepNodes(trace.value[i]!)) s.add(id)
  }
  return s
})

/** 当前步激活节点 id */
const traceCurrentNodes = computed(() =>
  step.value >= 0 && step.value < trace.value.length ? stepNodes(trace.value[step.value]!) : [],
)

function togglePlay() {
  if (playing.value) {
    playing.value = false
    return
  }
  if (!trace.value.length) return
  if (step.value >= trace.value.length - 1) step.value = 0
  else if (step.value < 0) step.value = 0
  playing.value = true
}

function resetTrace() {
  playing.value = false
  step.value = -1
}

watch([playing, speed], ([p]) => {
  if (traceTimer !== undefined) {
    window.clearInterval(traceTimer)
    traceTimer = undefined
  }
  if (p) {
    traceTimer = window.setInterval(() => {
      if (step.value >= trace.value.length - 1) {
        playing.value = false
        return
      }
      step.value++
    }, 850 / speed.value)
  }
})

onBeforeUnmount(() => {
  if (traceTimer !== undefined) window.clearInterval(traceTimer)
})

onMounted(() => {
  load()
  loadRuns()
})
</script>

<template>
  <div v-loading="loading" class="onto-page">
    <div class="page-head">
      <div>
        <div class="page-title">🧭 Ontology 调用链路</div>
        <div class="page-sub">流程图：引擎 → 工具（7）→ Action（6）/ Function（5）→ 对象（7）｜实线 = 有调用；虚线 = 未激活（静态拓扑）</div>
      </div>
      <div class="head-right">
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <span class="legend">
          <span class="lg lg-blue">引擎</span><span class="lg lg-green">工具</span
          ><span class="lg lg-orange">Action</span><span class="lg lg-cyan">Function</span><span class="lg lg-purple">对象</span>
          <span class="lg-tip">— 实线 = 有调用；虚线 = 未激活（静态拓扑）</span>
        </span>
      </div>
    </div>

    <!-- 调用链回放：选一次 run，逐步点亮真实调用链路（engine → 工具 → Action/Function → 对象） -->
    <el-card v-if="data" shadow="never" class="trace-card">
      <template #header><span class="overview-title">调用链回放</span></template>
      <div class="trace-bar">
        <el-select
          v-model="runId"
          placeholder="选择一次 run 回放"
          style="width: 360px"
          :loading="runsLoading"
          filterable
          @change="onRunChange"
        >
          <el-option v-for="r in runs" :key="r.id" :value="r.id" :label="runLabel(r)" />
        </el-select>
        <el-button-group>
          <el-button :disabled="!trace.length" title="重置" @click="resetTrace">⏮</el-button>
          <el-button
            :disabled="!trace.length"
            :type="playing ? 'warning' : 'primary'"
            @click="togglePlay"
          >{{ playing ? '⏸ 暂停' : '▶ 播放' }}</el-button>
        </el-button-group>
        <span v-if="trace.length" class="step-text">
          第 {{ step < 0 ? 0 : Math.min(step + 1, trace.length) }} / {{ trace.length }} 步
          <template v-if="step >= 0 && step < trace.length">
            · <b>{{ trace[step].name }}</b><template v-if="trace[step].target"> → {{ trace[step].target }}</template>
            <span class="step-ms">{{ trace[step].duration_ms ?? '-' }}ms</span>
            <span v-if="trace[step].ok === false" class="step-fail">✗ 失败</span>
          </template>
        </span>
        <el-radio-group v-model="speed" size="small" :disabled="!trace.length">
          <el-radio-button :value="1">×1</el-radio-button>
          <el-radio-button :value="2">×2</el-radio-button>
          <el-radio-button :value="4">×4</el-radio-button>
        </el-radio-group>
        <span v-if="!trace.length && runId" class="trace-empty">该 run 无工具调用明细（V5 迁移前的存量 run）</span>
      </div>
    </el-card>

    <div v-if="data" class="flow" :key="days">
      <svg class="flow-svg" :width="flow.w" :height="flow.h">
        <defs>
          <marker id="arr" markerWidth="9" markerHeight="9" refX="7" refY="4.5" orient="auto">
            <path d="M0,0 L9,4.5 L0,9 Z" fill="#4e5969" />
          </marker>
          <marker id="arr-dead" markerWidth="9" markerHeight="9" refX="7" refY="4.5" orient="auto">
            <path d="M0,0 L9,4.5 L0,9 Z" fill="#c9cdd4" />
          </marker>
        </defs>
        <path
          v-for="e in flow.edges"
          :key="e.d + e.lx"
          class="edge-line"
          :class="{ dead: e.dead, 'trace-active': traceEdges.has(e.from + '|' + e.to) }"
          :d="e.d"
          :marker-end="e.dead ? 'url(#arr-dead)' : 'url(#arr)'"
        />
        <text
          v-for="e in flow.edges.filter((x) => x.showLabel)"
          :key="'l' + e.d + e.lx"
          class="e-label"
          :x="e.lx"
          :y="e.ly"
          text-anchor="middle"
        >×{{ fmtInt(e.calls) }}<tspan v-if="e.avg_ms" dx="4" class="e-ms">{{ fmtInt(e.avg_ms) }}ms</tspan></text>
      </svg>
      <div v-for="c in flow.cols" :key="c.label" class="col-title" :style="{ left: c.x + 'px' }">{{ c.label }}</div>
      <div
        v-for="n in flow.nodes"
        :key="n.id"
        class="node node-abs"
        :class="[
          n.kind,
          { dead: n.kind !== 'object' && !nodeById(n.id)?.calls, clickable: n.kind === 'object' },
          {
            'trace-current': traceCurrentNodes.includes(n.id),
            'trace-visited': !traceCurrentNodes.includes(n.id) && traceVisitedNodes.has(n.id),
          },
        ]"
        :style="{ left: n.x + 'px', top: n.y + 'px', width: n.w + 'px' }"
        :title="n.kind === 'object' ? '点击下钻数据' : undefined"
        @click="n.kind === 'object' && nodeById(n.id) ? openDrill(nodeById(n.id)!) : undefined"
      >
        <span class="n-label">{{ nodeById(n.id)?.label }}</span>
        <template v-if="n.kind === 'object'">
          <span class="badge">记录 {{ fmtInt(nodeById(n.id)?.count ?? 0) }}</span>
          <span class="badge">字段 {{ fmtInt(nodeById(n.id)?.fields ?? 0) }}</span>
        </template>
        <template v-else>
          <span class="badge hot" v-if="nodeById(n.id)?.calls">×{{ fmtInt(nodeById(n.id)?.calls ?? 0) }}<i v-if="nodeById(n.id)?.avg_ms">{{ fmtInt(nodeById(n.id)?.avg_ms ?? 0) }}ms</i></span>
          <span class="badge fail" v-if="nodeById(n.id)?.fails">✗{{ fmtInt(nodeById(n.id)?.fails ?? 0) }}</span>
        </template>
      </div>
    </div>

    <!-- 对象数据总览：与上图共享同一次 graph 数据 -->
    <el-card v-if="data" shadow="never" class="overview-card">
      <template #header>
        <span class="overview-title">对象数据总览</span>
      </template>
      <el-table :data="layerNodes('object')" size="small">
        <el-table-column prop="label" label="对象" min-width="160" />
        <el-table-column label="记录数" width="120">
          <template #default="{ row }">{{ fmtInt(row.count ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="字段数" width="100">
          <template #default="{ row }">{{ fmtInt(row.fields ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openDrill(row)">数据</el-button>
            <el-button v-if="row.id === 'obj:customer' || row.id === 'obj:campaign'" size="small" @click="jumpTo(row)">跳转</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 对象数据下钻弹窗 -->
    <el-dialog v-model="drillVisible" :title="drillState ? `${drillState.label} · 数据下钻` : ''" width="860px" destroy-on-close>
      <template v-if="drillState">
        <el-table v-if="drillItems.length" v-loading="drillLoading" :data="drillItems" size="small" max-height="420">
          <el-table-column
            v-for="c in drillColumns"
            :key="c"
            :prop="c"
            :label="c"
            min-width="140"
            show-overflow-tooltip
          />
        </el-table>
        <el-empty v-else-if="!drillLoading" description="暂无数据" />
        <div class="drill-footer">
          <span class="drill-total">共 {{ fmtInt(drillTotal) }} 条</span>
          <el-button
            v-if="drillItems.length < drillTotal"
            size="small"
            :disabled="!drillNextToken"
            :loading="drillLoading"
            @click="loadMore"
          >加载更多</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.onto-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 18px;
  font-weight: 700;
  color: #1d2129;
}
.page-sub {
  font-size: 13px;
  color: #86909c;
  margin-top: 4px;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.legend {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #86909c;
}
.lg {
  padding: 2px 8px;
  border-radius: 6px;
  color: #fff;
  font-size: 12px;
}
.lg-blue { background: #3370ff; }
.lg-green { background: #00b42a; }
.lg-orange { background: #ff7d00; }
.lg-cyan { background: #13c2c2; }
.lg-purple { background: #722ed1; }
.lg-tip { color: #c9cdd4; }

.flow {
  position: relative;
  background: #fff;
  border: 1px solid #f0f1f3;
  border-radius: 12px;
  overflow: auto;
  min-width: 0;
}
.flow-svg {
  display: block;
}
.edge-line {
  fill: none;
  stroke: #4e5969;
  stroke-width: 1.6;
  stroke-linejoin: round;
}
.edge-line.dead {
  stroke: #c9cdd4;
  stroke-dasharray: 5 4;
  opacity: 0.8;
}
.e-label {
  font-size: 11px;
  fill: #4e5969;
  paint-order: stroke;
  stroke: #fff;
  stroke-width: 3px;
}
.e-ms {
  fill: #86909c;
  stroke: none;
}
.col-title {
  position: absolute;
  top: 12px;
  font-size: 12px;
  font-weight: 600;
  color: #86909c;
}
.node-abs {
  position: absolute;
  justify-content: center;
  box-sizing: border-box;
}
.node-abs.object {
  flex-wrap: wrap;
  row-gap: 2px;
}
.node {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid;
  font-size: 13px;
  white-space: nowrap;
}
.node .n-label {
  font-weight: 500;
  color: #1d2129;
}
.node.engine {
  background: #eef4ff;
  border-color: #3370ff;
  font-weight: 700;
  color: #1d2129;
  padding: 14px 16px;
}
.node.tool { background: #e8ffea; border-color: #00b42a; }
.node.action { background: #fff3e8; border-color: #ff7d00; }
.node.function { background: #e6fffb; border-color: #13c2c2; }
.node.object { background: #f9f0ff; border-color: #722ed1; cursor: pointer; }
.node.object:hover {
  border-color: #722ed1;
  box-shadow: 0 2px 8px rgba(114, 46, 209, 0.18);
}
.node.dead {
  background: #f7f8fa;
  border-color: #e5e6eb;
  opacity: 0.55;
}
.node.dead .n-label { color: #86909c; }

.badge {
  font-size: 11px;
  color: #1d2129;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  padding: 0 6px;
  line-height: 16px;
}
.badge i {
  font-style: normal;
  color: #86909c;
  margin-left: 3px;
}
.badge.fail {
  color: #f53f3f;
  border-color: #fadbd9;
  background: #ffece8;
}
.badge.hot {
  background: #fff;
  border-color: #3370ff;
  color: #3370ff;
}
.badge.hot i { color: #3370ff; }

.edge-line.trace-active {
  stroke: #d81e06;
  stroke-width: 2.4;
  stroke-dasharray: 10 7;
  stroke-linecap: butt;
  opacity: 1;
  animation: trace-flow 0.7s linear infinite;
}
@keyframes trace-flow {
  to {
    stroke-dashoffset: -17;
  }
}
.node-abs.trace-current {
  outline: 3px solid #d81e06;
  outline-offset: 2px;
  box-shadow: 0 0 14px rgba(216, 30, 6, 0.35);
  z-index: 2;
}
.node-abs.trace-visited {
  outline: 2px solid rgba(216, 30, 6, 0.45);
  outline-offset: 1px;
}
.trace-card {
  border-radius: 12px;
}
.trace-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.step-text {
  font-size: 13px;
  color: #1d2129;
}
.step-text b {
  color: #d81e06;
}
.step-ms {
  color: #86909c;
  margin-left: 8px;
}
.step-fail {
  color: #f53f3f;
  margin-left: 8px;
  font-weight: 600;
}
.trace-empty {
  font-size: 12px;
  color: #86909c;
}

.overview-card {
  border-radius: 12px;
}
.overview-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2129;
}
.drill-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}
.drill-total {
  font-size: 13px;
  color: #86909c;
}
</style>
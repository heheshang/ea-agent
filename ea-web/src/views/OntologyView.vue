<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { get } from '../api/http'

/**
 * Ontology 调用链路页：分层拓扑（引擎 → 工具 → Action/Function → 对象）+ 运行时调用热点。
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

function shortId(id: string): string {
  const i = id.indexOf(':')
  return i >= 0 ? id.slice(i + 1) : id
}

function layerNodes(type: OntologyNode['type']): OntologyNode[] {
  return (data.value?.nodes ?? []).filter((n) => n.type === type)
}

function layerEdges(fromType: string, toType: string): OntologyEdge[] {
  return (data.value?.edges ?? []).filter((e) => {
    const fromOk = fromType === 'engine' ? e.from === 'engine' : e.from.startsWith(fromType + ':')
    const toOk = toType === 'engine' ? e.to === 'engine' : e.to.startsWith(toType + ':')
    return fromOk && toOk
  })
}

function fmtInt(v: number | undefined): string {
  if (v === undefined || v === null) return ''
  return new Intl.NumberFormat('en-US').format(v)
}

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

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="onto-page">
    <div class="page-head">
      <div>
        <div class="page-title">🧭 Ontology 调用链路</div>
        <div class="page-sub">引擎 → 工具（7）→ Action（6）→ Function（5）→ 对象（7）｜调用热点实时标注，未调用节点置灰 = 拓扑静态展示</div>
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

    <div v-if="data" class="trace" :key="days">
      <!-- 引擎 -->
      <div class="layer">
        <div class="layer-title">引擎</div>
        <div class="node engine">{{ layerNodes('engine')[0]?.label }}</div>
      </div>

      <!-- 引擎 → 工具 -->
      <div class="edge-col">
        <div
          v-for="e in layerEdges('engine', 'tool')"
          :key="e.from + e.to"
          class="edge"
          :class="{ dead: !e.calls }"
        >
          <span class="e-to">{{ shortId(e.to) }}</span>
          <span class="badge hot" v-if="e.calls">×{{ fmtInt(e.calls) }}<i v-if="e.avg_ms">{{ fmtInt(e.avg_ms) }}ms</i></span>
          <span class="arrow">→</span>
        </div>
      </div>

      <!-- 工具 -->
      <div class="layer">
        <div class="layer-title">工具</div>
        <div
          v-for="n in layerNodes('tool')"
          :key="n.id"
          class="node tool"
          :class="{ dead: !n.calls }"
        >
          <span class="n-label">{{ n.label }}</span>
          <span class="badge" v-if="n.calls">×{{ fmtInt(n.calls) }}<i v-if="n.avg_ms">{{ fmtInt(n.avg_ms) }}ms</i></span>
          <span class="badge fail" v-if="n.fails">✗{{ n.fails }}</span>
        </div>
      </div>

      <!-- 工具 → Action（applyAction） -->
      <div class="edge-col">
        <div
          v-for="e in layerEdges('tool', 'action')"
          :key="e.from + e.to"
          class="edge"
          :class="{ dead: !e.calls }"
        >
          <span class="e-to">{{ shortId(e.to) }}</span>
          <span class="badge hot" v-if="e.calls">×{{ fmtInt(e.calls) }}<i v-if="e.avg_ms">{{ fmtInt(e.avg_ms) }}ms</i></span>
          <span class="arrow">→</span>
        </div>
      </div>

      <!-- Action -->
      <div class="layer">
        <div class="layer-title">Action</div>
        <div
          v-for="n in layerNodes('action')"
          :key="n.id"
          class="node action"
          :class="{ dead: !n.calls }"
        >
          <span class="n-label">{{ n.label }}</span>
          <span class="badge" v-if="n.calls">×{{ fmtInt(n.calls) }}<i v-if="n.avg_ms">{{ fmtInt(n.avg_ms) }}ms</i></span>
          <span class="badge fail" v-if="n.fails">✗{{ n.fails }}</span>
        </div>
      </div>

      <!-- Action → 对象 -->
      <div class="edge-col">
        <div
          v-for="e in layerEdges('action', 'obj')"
          :key="e.from + e.to"
          class="edge"
          :class="{ dead: !e.calls }"
        >
          <span class="e-to">{{ shortId(e.to) }}</span>
          <span class="arrow">→</span>
        </div>
      </div>

      <!-- 工具 → Function -->
      <div class="edge-col">
        <div
          v-for="e in layerEdges('tool', 'function')"
          :key="e.from + e.to"
          class="edge"
          :class="{ dead: !e.calls }"
        >
          <span class="e-to">{{ shortId(e.to) }}</span>
          <span class="arrow">→</span>
        </div>
      </div>

      <!-- Function -->
      <div class="layer">
        <div class="layer-title">Function</div>
        <div
          v-for="n in layerNodes('function')"
          :key="n.id"
          class="node function"
          :class="{ dead: !n.calls }"
        >
          <span class="n-label">{{ n.label }}</span>
          <span class="badge" v-if="n.calls">×{{ fmtInt(n.calls) }}<i v-if="n.avg_ms">{{ fmtInt(n.avg_ms) }}ms</i></span>
          <span class="badge fail" v-if="n.fails">✗{{ n.fails }}</span>
        </div>
      </div>

      <!-- Function → 对象 -->
      <div class="edge-col">
        <div
          v-for="e in layerEdges('function', 'obj')"
          :key="e.from + e.to"
          class="edge"
          :class="{ dead: !e.calls }"
        >
          <span class="e-to">{{ shortId(e.to) }}</span>
          <span class="arrow">→</span>
        </div>
      </div>

      <!-- 对象 -->
      <div class="layer">
        <div class="layer-title">对象</div>
        <div
          v-for="n in layerNodes('object')"
          :key="n.id"
          class="node object"
          title="点击下钻数据"
          @click="openDrill(n)"
        >
          <span class="n-label">{{ n.label }}</span>
          <span class="badge">记录 {{ fmtInt(n.count ?? 0) }}</span>
          <span class="badge">字段 {{ fmtInt(n.fields ?? 0) }}</span>
        </div>
      </div>

      <!-- 工具 → 对象 -->
      <div class="edge-col">
        <div
          v-for="e in layerEdges('tool', 'obj')"
          :key="e.from + e.to"
          class="edge"
          :class="{ dead: !data.nodes.find((n) => n.id === e.from)?.calls }"
        >
          <span class="e-to">{{ shortId(e.to) }}</span>
          <span class="arrow">→</span>
        </div>
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

.trace {
  display: flex;
  gap: 18px;
  align-items: flex-start;
  padding: 20px;
  background: #fff;
  border: 1px solid #f0f1f3;
  border-radius: 12px;
  overflow-x: auto;
  min-width: 0;
}
.layer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 150px;
}
.layer-title {
  font-size: 12px;
  font-weight: 600;
  color: #86909c;
  margin-bottom: 2px;
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

.edge-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 24px;
  min-width: 120px;
}
.edge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #4e5969;
  white-space: nowrap;
}
.edge .arrow {
  color: #4e5969;
  font-weight: 700;
}
.edge.dead {
  color: #c9cdd4;
}
.edge.dead .arrow {
  color: #c9cdd4;
}
.edge .e-to {
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.edge.dead .e-to {
  text-decoration: line-through;
  text-decoration-style: dotted;
  opacity: 0.7;
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
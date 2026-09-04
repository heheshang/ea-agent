<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '../api/http'

/**
 * Ontology 调用链路页：分层拓扑（引擎 → 工具 → Action → 对象）+ 运行时调用热点。
 * 节点/边为代码事实（TypeRegistry 7 对象 / ActionRegistry 5 Action / AgentToolRegistry 10 工具），
 * 运行时统计来自 agent_run.tool_calls（calls / avg_ms / fails 徽章）。
 * 未调用过的节点与静态边置灰虚线展示——图同时是「Ontology 架构图」与「调用热点图」。
 */
interface OntologyNode {
  id: string
  type: 'engine' | 'tool' | 'action' | 'object'
  label: string
  calls?: number
  avg_ms?: number
  fails?: number
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

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="onto-page">
    <div class="page-head">
      <div>
        <div class="page-title">🧭 Ontology 调用链路</div>
        <div class="page-sub">引擎 → 工具（10）→ Action（5）→ 对象（7）｜调用热点实时标注，未调用节点置灰 = 拓扑静态展示</div>
      </div>
      <div class="head-right">
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <span class="legend">
          <span class="lg lg-blue">引擎</span><span class="lg lg-green">工具</span
          ><span class="lg lg-orange">Action</span><span class="lg lg-purple">对象</span>
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

      <!-- 对象 -->
      <div class="layer">
        <div class="layer-title">对象</div>
        <div v-for="n in layerNodes('object')" :key="n.id" class="node object">
          <span class="n-label">{{ n.label }}</span>
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
.node.object { background: #f9f0ff; border-color: #722ed1; }
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
</style>
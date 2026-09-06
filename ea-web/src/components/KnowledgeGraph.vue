<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get } from '../api/http'
import type { KnowledgeEntry, KnowledgeGraph, KnowledgeGraphEdge } from '../api/types'

/** 记录类别配色（与 KnowledgeView 标签色一致）。 */
const TYPE_COLORS: Record<string, string> = {
  rule: '#3370ff',
  constraint: '#f53f3f',
  decision: '#722ed1',
  rationale: '#0fc6c2',
  lesson: '#ff7d00',
  fact: '#00b42a',
  anti_pattern: '#c41d7f',
}
const DEFAULT_COLOR = '#86909c'

/** 关系类型配色。 */
const REL_COLORS: Record<string, string> = {
  supersedes: '#f77234',
  supports: '#00b42a',
  conflicts: '#f53f3f',
  refines: '#14c9c9',
  related: '#86909c',
}
const REL_LABELS: Record<string, string> = {
  supersedes: '取代',
  supports: '支撑',
  conflicts: '冲突',
  refines: '细化',
  related: '相关',
}

const W = 900
const H = 560
const CX = W / 2
const CY = H / 2

const loading = ref(true)
const error = ref('')
const nodes = ref<KnowledgeEntry[]>([])
const edges = ref<KnowledgeGraphEdge[]>([])
const selectedId = ref<number | null>(null)

const selected = computed(() => nodes.value.find((n) => n.id === selectedId.value) ?? null)
const typeColor = (t?: string) => (t ? TYPE_COLORS[t] ?? DEFAULT_COLOR : DEFAULT_COLOR)
const relColor = (r: string) => REL_COLORS[r] ?? DEFAULT_COLOR

/** 布局位置查找表（渲染前由 runLayout 结果重建）。 */
const posMap = ref(new Map<number, { x: number; y: number }>())
function posOf(id: number) {
  return posMap.value.get(id) ?? { x: 0, y: 0 }
}

/** 力导向布局：斥力 + 弹簧 + 中心引力 + 阻尼，320 轮迭代后收敛为静态快照。 */
function runLayout(list: KnowledgeEntry[], rels: KnowledgeGraphEdge[]) {
  const pos = new Map<number, { x: number; y: number; vx: number; vy: number }>()
  const n = list.length
  list.forEach((node, i) => {
    const angle = (i / Math.max(n, 1)) * Math.PI * 2
    const r = 190 + (i % 3) * 24
    pos.set(node.id, {
      x: CX + Math.cos(angle) * r,
      y: CY + Math.sin(angle) * r,
      vx: (Math.random() - 0.5) * 20,
      vy: (Math.random() - 0.5) * 20,
    })
  })
  const REP = 2600 // 节点互斥强度（像素²）
  const SPRING = 0.045 // 边弹簧系数
  const REST = 95 // 边理想长度
  const GRAV = 0.006
  const DAMP = 0.82
  const MAX_SPEED = 9

  const points = [...pos.values()]
  for (let tick = 0; tick < 320; tick++) {
    // 两两斥力
    for (let a = 0; a < points.length; a++) {
      for (let b = a + 1; b < points.length; b++) {
        const pa = points[a]
        const pb = points[b]
        let dx = pb.x - pa.x
        let dy = pb.y - pa.y
        let d2 = dx * dx + dy * dy
        if (d2 < 1) {
          dx = (Math.random() - 0.5) * 4
          dy = (Math.random() - 0.5) * 4
          d2 = dx * dx + dy * dy
        }
        const d = Math.sqrt(d2)
        const f = REP / d2
        const fx = (dx / d) * f
        const fy = (dy / d) * f
        pa.vx -= fx
        pa.vy -= fy
        pb.vx += fx
        pb.vy += fy
      }
    }
    // 边弹簧
    for (const e of rels) {
      const pa = pos.get(e.source)
      const pb = pos.get(e.target)
      if (!pa || !pb) continue
      const dx = pb.x - pa.x
      const dy = pb.y - pa.y
      const d = Math.sqrt(dx * dx + dy * dy) || 1
      const f = SPRING * (d - REST)
      const fx = (dx / d) * f
      const fy = (dy / d) * f
      pa.vx += fx
      pa.vy += fy
      pb.vx -= fx
      pb.vy -= fy
    }
    // 中心引力 + 阻尼 + 位移
    for (const p of points) {
      const gx = CX - p.x
      const gy = CY - p.y
      p.vx += gx * GRAV
      p.vy += gy * GRAV
      p.vx *= DAMP
      p.vy *= DAMP
      const sp = Math.hypot(p.vx, p.vy)
      if (sp > MAX_SPEED) {
        p.vx = (p.vx / sp) * MAX_SPEED
        p.vy = (p.vy / sp) * MAX_SPEED
      }
      p.x += p.vx
      p.y += p.vy
    }
  }
  return list.map((node) => {
    const p = pos.get(node.id)!
    return { id: node.id, x: p.x, y: p.y }
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await get<KnowledgeGraph>('/knowledge/graph')
    nodes.value = data.nodes
    edges.value = data.edges
    posMap.value = new Map(runLayout(data.nodes, data.edges).map((p) => [p.id, { x: p.x, y: p.y }]))
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function label(n: KnowledgeEntry) {
  return n.title.length > 11 ? n.title.slice(0, 11) + '…' : n.title
}

/** 生命周期样式：superseded 虚线外环灰描；obsolete 整体灰化。 */
function ringClass(n: KnowledgeEntry) {
  if (n.lifecycle === 'superseded') return 'kg-ring-superseded'
  if (n.lifecycle === 'obsolete') return 'kg-ring-obsolete'
  return ''
}

onMounted(load)
</script>

<template>
  <div class="kg-wrap">
    <div v-if="loading" class="kg-empty">图谱加载中…</div>
    <div v-else-if="error" class="kg-empty">加载失败：{{ error }}</div>
    <div v-else-if="!nodes.length" class="kg-empty">暂无知识条目，先创建条目再查看图谱</div>
    <template v-else>
      <svg class="kg-svg" :viewBox="`0 0 ${W} ${H}`" @click="selectedId = null">
        <g v-for="(e, i) in edges" :key="`e${i}`">
          <line
            class="kg-edge"
            :x1="posOf(e.source).x"
            :y1="posOf(e.source).y"
            :x2="posOf(e.target).x"
            :y2="posOf(e.target).y"
            :stroke="relColor(e.relation)"
            :stroke-width="e.relation === 'supersedes' ? 1.8 : 1.3"
            :stroke-dasharray="e.relation === 'supersedes' ? '6 4' : undefined"
            :title="`${REL_LABELS[e.relation] ?? e.relation}`"
          />
          <text
            class="kg-edge-label"
            :x="(posOf(e.source).x + posOf(e.target).x) / 2"
            :y="(posOf(e.source).y + posOf(e.target).y) / 2 - 6"
            :fill="relColor(e.relation)"
          >
            {{ REL_LABELS[e.relation] ?? e.relation }}
          </text>
        </g>
        <g
          v-for="n in nodes"
          :key="n.id"
          class="kg-node"
          :class="{ 'kg-node-selected': selectedId === n.id }"
          :transform="`translate(${posOf(n.id).x}, ${posOf(n.id).y})`"
          @click.stop="selectedId = n.id"
        >
          <circle r="30" :fill="typeColor(n.recordType)" fill-opacity="0.16" :stroke="typeColor(n.recordType)" stroke-width="2" :class="ringClass(n)" />
          <circle r="8" :fill="typeColor(n.recordType)" :stroke="n.lifecycle === 'obsolete' ? '#86909c' : '#fff'" stroke-width="1.5" />
          <text class="kg-node-label" :y="48" :text-anchor="'middle'">{{ label(n) }}</text>
        </g>
      </svg>

      <!-- 选中节点信息卡 -->
      <div v-if="selected" class="kg-card" @click.stop>
        <div class="kg-card-head">
          <span class="kg-card-type" :style="{ background: typeColor(selected.recordType) }">
            {{ (selected.recordType || 'unknown') }}
          </span>
          <span class="kg-card-life" :class="`kg-life-${selected.lifecycle || 'active'}`">
            {{ selected.lifecycle || 'active' }}
          </span>
        </div>
        <div class="kg-card-title">{{ selected.title }}</div>
        <p class="kg-card-content">{{ selected.content }}</p>
        <div v-if="selected.tags?.length" class="kg-card-tags">
          <span v-for="t in selected.tags" :key="t" class="kg-tag">{{ t }}</span>
        </div>
      </div>

      <!-- 图例 -->
      <div class="kg-legend" @click.stop>
        <div class="kg-legend-title">关系</div>
        <div v-for="(color, rel) in REL_COLORS" :key="rel" class="kg-legend-item">
          <span class="kg-legend-line" :style="{ background: color }" />
          {{ REL_LABELS[rel] }}
        </div>
        <div class="kg-legend-title kg-legend-title2">类型</div>
        <div v-for="(color, type) in TYPE_COLORS" :key="type" class="kg-legend-item">
          <span class="kg-legend-dot" :style="{ background: color }" />
          {{ type }}
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.kg-wrap {
  position: relative;
  height: 560px;
}
.kg-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #86909c;
}
.kg-svg {
  width: 100%;
  height: 100%;
  background: linear-gradient(180deg, #f7f9fc 0%, #eef2f8 100%);
  border-radius: 8px;
}
.kg-node {
  cursor: pointer;
}
.kg-node-selected circle:first-child {
  filter: drop-shadow(0 0 6px rgba(51, 112, 255, 0.55));
}
.kg-ring-superseded {
  stroke-dasharray: 5 4;
  stroke-opacity: 0.55;
}
.kg-ring-obsolete {
  stroke: #86909c;
  stroke-dasharray: 5 4;
  stroke-opacity: 0.4;
}
.kg-node-label {
  font-size: 11px;
  fill: #4e5969;
  text-anchor: middle;
  pointer-events: none;
}
.kg-edge {
  pointer-events: none;
}
.kg-edge-label {
  font-size: 10px;
  text-anchor: middle;
  pointer-events: none;
  opacity: 0.85;
}
.kg-card {
  position: absolute;
  right: 14px;
  top: 14px;
  width: 300px;
  max-height: 340px;
  overflow: auto;
  background: rgba(255, 255, 255, 0.97);
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.12);
  padding: 14px;
  cursor: default;
}
.kg-card-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.kg-card-type {
  color: #fff;
  font-size: 11px;
  border-radius: 4px;
  padding: 1px 8px;
}
.kg-card-life {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 4px;
  background: #e8f3ff;
  color: #3370ff;
}
.kg-life-superseded {
  background: #fff7e8;
  color: #ff7d00;
}
.kg-life-obsolete {
  background: #f2f3f5;
  color: #86909c;
}
.kg-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 8px;
}
.kg-card-content {
  font-size: 12px;
  line-height: 1.7;
  color: #4e5969;
  margin: 0 0 10px;
  white-space: pre-wrap;
}
.kg-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.kg-tag {
  font-size: 11px;
  background: #f2f3f5;
  color: #4e5969;
  border-radius: 4px;
  padding: 1px 8px;
}
.kg-legend {
  position: absolute;
  left: 14px;
  bottom: 14px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #e5e6eb;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 11px;
  color: #4e5969;
  display: grid;
  grid-template-columns: repeat(2, auto);
  gap: 4px 14px;
}
.kg-legend-title {
  grid-column: 1 / -1;
  font-weight: 600;
  color: #1d2129;
}
.kg-legend-title2 {
  margin-top: 4px;
}
.kg-legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
}
.kg-legend-line {
  width: 16px;
  height: 2px;
  display: inline-block;
}
.kg-legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
</style>
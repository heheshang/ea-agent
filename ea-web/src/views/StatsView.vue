<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '../api/http'

/**
 * 统计看板：Agent 多维度统计（token 构成/耗时/成本/缓存命中/工具与 skill/提示词版本/会话/模型）。
 * 无图表库依赖——卡片 + 表格 + 简易 div 宽度条（勿引 echarts）。
 */
interface StatsData {
  summary: Record<string, number>
  status_dist: Array<{ status: string; count: number }>
  daily: Array<{ date: string; runs: number; tokens: number; cost: number; avg_duration_s: number }>
  tools: Array<{ name: string; calls: number; fails: number; fail_rate: number; avg_ms: number; p95_ms: number }>
  skills: Array<{ name: string; calls: number; fails: number; fail_rate: number; avg_ms: number; p95_ms: number }>
  models: Array<{ model: string; runs: number; tokens: number; cost: number; avg_model_ms: number }>
  sessions: Array<{ session_id: string; runs: number; tokens: number; cost: number; avg_duration_s: number }>
  prompt_versions: Array<{ version: string; runs: number; sys_prompt_len: number; cost: number }>
  top_slow_runs: Array<{ id: number; goal: string; status: string; duration_s: number; cost: number }>
}

const days = ref(7)
const loading = ref(false)
const data = ref<StatsData | null>(null)

function fmt(v: number | undefined, digits = 1): string {
  if (v === undefined || v === null || Number.isNaN(v)) return '-'
  return (v as number).toFixed(digits)
}

function fmtInt(v: number | undefined): string {
  if (v === undefined || v === null) return '-'
  return new Intl.NumberFormat('en-US').format(v)
}

function money(v: number | undefined): string {
  if (v === undefined || v === null) return '-'
  return `$${v.toFixed(4)}`
}

function pct(v: number | undefined): string {
  if (v === undefined || v === null) return '-'
  return `${(v * 100).toFixed(1)}%`
}

async function load() {
  loading.value = true
  try {
    data.value = await get<StatsData>('/agent/stats', { days: days.value, session_id: undefined })
  } finally {
    loading.value = false
  }
}

function tokenParts(): Array<{ label: string; value: number; color: string }> {
  const s = data.value?.summary ?? {}
  const total = (s.input_tokens ?? 0) + (s.output_tokens ?? 0)
  if (total <= 0) return []
  return [
    { label: '输入', value: s.input_tokens ?? 0, color: '#3370ff' },
    { label: '输出', value: s.output_tokens ?? 0, color: '#7be0a6' },
    { label: '缓存命中', value: s.cached_tokens ?? 0, color: '#f7ba1e' },
  ]
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="stats-page">
    <div class="page-head">
      <div>
        <div class="page-title">📊 Agent 统计看板</div>
        <div class="page-sub">token 构成 / 耗时 / 成本 / 缓存命中 / 工具与 skill 调用 / 提示词版本</div>
      </div>
      <el-radio-group v-model="days" @change="load">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
    </div>

    <template v-if="data">
      <!-- 汇总卡片 -->
      <div class="cards">
        <div class="card"><span class="k">总运行</span><span class="v">{{ fmtInt(data.summary.runs) }}</span></div>
        <div class="card"><span class="k">成功率</span><span class="v">{{ pct(data.summary.success_rate) }}</span></div>
        <div class="card"><span class="k">平均耗时</span><span class="v">{{ fmt(data.summary.avg_duration_s) }}s</span></div>
        <div class="card"><span class="k">P95 耗时</span><span class="v">{{ fmt(data.summary.p95_duration_s) }}s</span></div>
        <div class="card"><span class="k">总成本</span><span class="v">{{ money(data.summary.total_cost) }}</span></div>
        <div class="card"><span class="k">单次均成本</span><span class="v">{{ money(data.summary.cost_per_run) }}</span></div>
        <div class="card"><span class="k">缓存命中率</span><span class="v">{{ pct(data.summary.cache_hit_rate) }}</span></div>
        <div class="card"><span class="k">总 token</span><span class="v">{{ fmtInt(data.summary.total_tokens) }}</span></div>
      </div>

      <!-- token 构成 + 关键指标 -->
      <div class="grid-2">
        <el-card shadow="never" class="panel">
          <template #header>Token 构成（输入/输出/缓存命中）</template>
          <div v-if="tokenParts().length" class="token-bar">
            <div
              v-for="p in tokenParts()"
              :key="p.label"
              class="token-seg"
              :style="{ width: (p.value / ((data!.summary.input_tokens ?? 0) + (data!.summary.output_tokens ?? 0)) * 100) + '%', background: p.color }"
              :title="`${p.label}: ${fmtInt(p.value)}`"
            />
          </div>
          <div v-else class="empty-tip">暂无 token 数据（旧 run 未采集）</div>
          <div class="token-legend">
            <span v-for="p in tokenParts()" :key="p.label">
              <i :style="{ background: p.color }" />{{ p.label }} {{ fmtInt(p.value) }}
            </span>
          </div>
          <el-descriptions :column="2" size="small" class="indices">
            <el-descriptions-item label="模型调用耗时(均)">{{ fmt(data.summary.avg_model_ms, 0) }}ms</el-descriptions-item>
            <el-descriptions-item label="输出 TPS">{{ fmt(data.summary.avg_tps) }}</el-descriptions-item>
            <el-descriptions-item label="运行状态分布">
              <span v-for="s in data.status_dist" :key="s.status" class="status-chip">{{ s.status }} {{ s.count }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="工具调用">{{ fmtInt(data.summary.tool_calls_total) }} 次 / 失败 {{ fmtInt(data.summary.tool_fail_total) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>按日趋势（UTCTimeZone）</template>
          <el-table :data="data.daily" size="small" max-height="280">
            <el-table-column prop="date" label="日期" width="110" />
            <el-table-column prop="runs" label="运行" width="70" />
            <el-table-column prop="tokens" label="Token" min-width="90">
              <template #default="{ row }">{{ fmtInt(row.tokens) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="90">
              <template #default="{ row }">{{ money(row.cost) }}</template>
            </el-table-column>
            <el-table-column label="均耗时" width="90">
              <template #default="{ row }">{{ fmt(row.avg_duration_s) }}s</template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>

      <!-- 工具 / skill -->
      <div class="grid-2">
        <el-card shadow="never" class="panel">
          <template #header>工具调用 TOP10（含循环检测：单 run > 200 次截断）</template>
          <el-table :data="data.tools" size="small" max-height="300">
            <el-table-column prop="name" label="工具" min-width="170" />
            <el-table-column prop="calls" label="调用" width="70" />
            <el-table-column prop="fails" label="失败" width="70" />
            <el-table-column label="失败率" width="80">
              <template #default="{ row }">{{ pct(row.fail_rate) }}</template>
            </el-table-column>
            <el-table-column label="均耗时" width="80">
              <template #default="{ row }">{{ fmt(row.avg_ms, 0) }}ms</template>
            </el-table-column>
            <el-table-column label="P95" width="80">
              <template #default="{ row }">{{ fmt(row.p95_ms, 0) }}ms</template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>Skill 调用（系统提示词维度）</template>
          <el-table v-if="data.skills.length" :data="data.skills" size="small" max-height="300">
            <el-table-column prop="name" label="Skill" min-width="170" />
            <el-table-column prop="calls" label="调用" width="70" />
            <el-table-column prop="fails" label="失败" width="70" />
            <el-table-column label="均耗时" width="80">
              <template #default="{ row }">{{ fmt(row.avg_ms, 0) }}ms</template>
            </el-table-column>
          </el-table>
          <div v-else class="empty-tip">近 {{ days }} 天无 skill 调用</div>
        </el-card>
      </div>

      <!-- 会话 / 模型 / 提示词版本 / 慢 run -->
      <div class="grid-2">
        <el-card shadow="never" class="panel">
          <template #header>会话 TOP10</template>
          <el-table :data="data.sessions" size="small" max-height="260">
            <el-table-column prop="session_id" label="会话 ID" min-width="150" show-overflow-tooltip />
            <el-table-column prop="runs" label="运行" width="70" />
            <el-table-column prop="tokens" label="Token" width="100">
              <template #default="{ row }">{{ fmtInt(row.tokens) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="90">
              <template #default="{ row }">{{ money(row.cost) }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>模型 / 提示词版本</template>
          <el-table :data="data.models" size="small" max-height="120">
            <el-table-column prop="model" label="模型" min-width="140" />
            <el-table-column prop="runs" label="运行" width="70" />
            <el-table-column prop="tokens" label="Token" width="90">
              <template #default="{ row }">{{ fmtInt(row.tokens) }}</template>
            </el-table-column>
            <el-table-column label="均模型耗时" width="90">
              <template #default="{ row }">{{ fmt(row.avg_model_ms, 0) }}ms</template>
            </el-table-column>
          </el-table>
          <el-table :data="data.prompt_versions" size="small" max-height="120" class="mt">
            <el-table-column prop="version" label="提示词版本" min-width="110" />
            <el-table-column prop="runs" label="运行" width="70" />
            <el-table-column label="提示词长度" width="100">
              <template #default="{ row }">{{ fmtInt(row.sys_prompt_len) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="90">
              <template #default="{ row }">{{ money(row.cost) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>

      <el-card shadow="never" class="panel">
        <template #header>最慢 5 次运行</template>
        <el-table :data="data.top_slow_runs" size="small">
          <el-table-column prop="id" label="Run ID" width="90" />
          <el-table-column prop="goal" label="目标" min-width="220" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ fmt(row.duration_s) }}s</template>
          </el-table-column>
          <el-table-column label="成本" width="90">
            <template #default="{ row }">{{ money(row.cost) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.stats-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
.cards {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 12px;
}
.card {
  background: #fff;
  border: 1px solid #f0f1f3;
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.card .k {
  font-size: 12px;
  color: #86909c;
}
.card .v {
  font-size: 20px;
  font-weight: 700;
  color: #1d2129;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.panel {
  border-radius: 12px;
  border-color: #f0f1f3;
}
.panel :deep(.el-card__header) {
  font-weight: 600;
  color: #1d2129;
}
.token-bar {
  display: flex;
  height: 22px;
  border-radius: 6px;
  overflow: hidden;
  background: #f2f3f5;
}
.token-seg {
  height: 100%;
}
.token-legend {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  font-size: 13px;
  color: #4e5969;
}
.token-legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  margin-right: 5px;
}
.indices {
  margin-top: 16px;
}
.status-chip {
  display: inline-block;
  background: #f2f3f5;
  border-radius: 4px;
  padding: 1px 6px;
  margin-right: 6px;
  font-size: 12px;
}
.empty-tip {
  color: #86909c;
  font-size: 13px;
  padding: 12px 0;
}
.mt {
  margin-top: 14px;
}
@media (max-width: 1400px) {
  .cards {
    grid-template-columns: repeat(4, 1fr);
  }
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
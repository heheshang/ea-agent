<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '../api/http'
import type { Row } from '../api/types'

/**
 * 存留看板：触达客户回访留存——首次成功触达（delivery=SENT）后 D1/D3/D7/D30 累计回访率。
 * 无图表库依赖——卡片 + 表格 + 简易 div 宽度条（勿引 echarts）。
 */
interface CohortRow {
  date: string
  base: number
  d1: number
  d3: number
  d7: number
  d30: number
  d1_rate: number
  d3_rate: number
  d7_rate: number
  d30_rate: number
}
interface CampaignRet {
  campaign_id: number
  name: string
  touches: number
  customers: number
  d1_rate: number
  d3_rate: number
  d7_rate: number
  d30_rate: number
}
interface RetentionData {
  summary: Record<string, number>
  cohorts: CohortRow[]
  campaigns: CampaignRet[]
  event_types: string[]
}

/** 回访窗口列（累计口径：触达后 N×24h 内回访客户占比）。 */
const WINDOWS = [
  { key: 'd1', label: 'D1·1日' },
  { key: 'd3', label: 'D3·3日' },
  { key: 'd7', label: 'D7·7日' },
  { key: 'd30', label: 'D30·30日' },
]

const days = ref(30)
const campaignId = ref<number | null>(null)
const eventType = ref('')
const loading = ref(false)
const loaded = ref(false)
const data = ref<RetentionData | null>(null)
const campaigns = ref<Row[]>([])

function fmtInt(v: number | undefined): string {
  if (v === undefined || v === null) return '-'
  return new Intl.NumberFormat('en-US').format(v)
}

function pct(v: number | undefined): string {
  if (v === undefined || v === null) return '-'
  return `${(v * 100).toFixed(1)}%`
}

function barWidth(v: number | undefined): string {
  return `${Math.round((v ?? 0) * 100)}%`
}

async function load() {
  loading.value = true
  try {
    data.value = await get<RetentionData>('/retention', {
      days: days.value,
      campaign_id: campaignId.value ?? undefined,
      event_type: eventType.value || undefined,
    })
  } finally {
    loaded.value = true
    loading.value = false
  }
}

onMounted(async () => {
  const page = await get<{ items: Row[]; total: number }>('/campaigns')
  campaigns.value = page.items ?? []
  await load()
})
</script>

<template>
  <div v-loading="loading" class="stats-page">
    <div class="page-head">
      <div>
        <div class="page-title">📈 存留看板</div>
        <div class="page-sub">触达客户回访留存：首次成功触达后的 D1/D3/D7/D30 累计回访率（UTC；窗口不足时后段为已观测值）</div>
      </div>
      <div class="controls">
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-select v-model="campaignId" clearable placeholder="全部活动" class="ctl" @change="load">
          <el-option v-for="c in campaigns" :key="String(c.id)" :label="String(c.name)" :value="Number(c.id)" />
        </el-select>
        <el-select v-model="eventType" clearable placeholder="回访事件（全部）" class="ctl" @change="load">
          <el-option v-for="t in data?.event_types ?? []" :key="t" :label="t" :value="t" />
        </el-select>
      </div>
    </div>

    <template v-if="data">
      <!-- 汇总卡片 -->
      <div class="cards cards-5">
        <div class="card"><span class="k">触达客户（去重）</span><span class="v">{{ fmtInt(data.summary.cohort_customers) }}</span></div>
        <div class="card"><span class="k">触达次数</span><span class="v">{{ fmtInt(data.summary.deliveries) }}</span></div>
        <div class="card"><span class="k">目标事件</span><span class="v">{{ fmtInt(data.summary.target_events) }}</span></div>
        <div class="card"><span class="k">回访客户</span><span class="v">{{ fmtInt(data.summary.return_customers) }}</span></div>
        <div class="card"><span class="k">平均回访间隔</span><span class="v">{{ (data.summary.avg_return_hours ?? 0).toFixed(1) }}<i class="unit">h</i></span></div>
      </div>

      <!-- 留存曲线 + 队列表 -->
      <div class="grid-2">
        <el-card shadow="never" class="panel">
          <template #header>回访留存曲线（累计回访率）</template>
          <div v-if="data.summary.cohort_customers" class="curve">
            <div v-for="w in WINDOWS" :key="w.key" class="curve-row">
              <span class="curve-label">{{ w.label }}</span>
              <div class="curve-track">
                <div class="curve-fill" :style="{ width: barWidth(data.summary[w.key + '_rate']) }" />
              </div>
              <span class="curve-val">
                {{ pct(data.summary[w.key + '_rate']) }}
                <i>{{ fmtInt(data.summary[w.key + '_count']) }} 人</i>
              </span>
            </div>
          </div>
          <div v-else class="empty-tip">近 {{ days }} 天无成功触达，无法计算留存</div>
          <el-descriptions :column="1" size="small" class="indices">
            <el-descriptions-item label="口径">
              队列 = 窗口内每位客户首次成功触达日；回访 = 触达后 1/3/7/30 天内产生目标事件
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>触达日队列（按日）</template>
          <el-table v-if="data.cohorts.length" :data="data.cohorts" size="small" max-height="420">
            <el-table-column prop="date" label="触达日" width="110" />
            <el-table-column prop="base" label="客户" width="70" />
            <el-table-column v-for="w in WINDOWS" :key="w.key" :label="w.label">
              <template #default="{ row }">
                <span class="cell-count">{{ row[w.key] }}</span>
                <span class="cell-pct">{{ pct(row[w.key + '_rate']) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="empty-tip">近 {{ days }} 天无触达记录</div>
        </el-card>
      </div>

      <!-- 活动维 -->
      <el-card shadow="never" class="panel">
        <template #header>活动留存对比（按活动内首次触达队列）</template>
        <el-table v-if="data.campaigns.length" :data="data.campaigns" size="small">
          <el-table-column prop="name" label="活动" min-width="200" show-overflow-tooltip />
          <el-table-column prop="touches" label="触达次数" width="90" />
          <el-table-column prop="customers" label="客户数" width="80" />
          <el-table-column label="D1" width="90">
            <template #default="{ row }">{{ pct(row.d1_rate) }}</template>
          </el-table-column>
          <el-table-column label="D3" width="90">
            <template #default="{ row }">{{ pct(row.d3_rate) }}</template>
          </el-table-column>
          <el-table-column label="D7" width="90">
            <template #default="{ row }">{{ pct(row.d7_rate) }}</template>
          </el-table-column>
          <el-table-column label="D30" width="90">
            <template #default="{ row }">{{ pct(row.d30_rate) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="empty-tip">近 {{ days }} 天无活动触达记录</div>
      </el-card>
    </template>
    <div v-else-if="loaded" class="empty-tip">近 {{ days }} 天无触达数据</div>
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
  flex-wrap: wrap;
  gap: 10px;
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
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ctl {
  width: 170px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 12px;
}
.cards-5 {
  grid-template-columns: repeat(5, 1fr);
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
  display: flex;
  align-items: baseline;
}
.card .unit {
  font-size: 12px;
  font-weight: 500;
  color: #86909c;
  margin-left: 4px;
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
.curve {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.curve-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.curve-label {
  width: 74px;
  font-size: 13px;
  color: #4e5969;
  flex-shrink: 0;
}
.curve-track {
  flex: 1;
  height: 22px;
  border-radius: 6px;
  background: #f2f3f5;
  overflow: hidden;
}
.curve-fill {
  height: 100%;
  border-radius: 6px;
  background: linear-gradient(90deg, #3370ff, #7be0a6);
  transition: width 0.3s;
}
.curve-val {
  width: 110px;
  font-size: 13px;
  font-weight: 600;
  color: #1d2129;
  text-align: right;
  flex-shrink: 0;
}
.curve-val i {
  font-style: normal;
  font-weight: 400;
  color: #86909c;
  margin-left: 6px;
}
.indices {
  margin-top: 16px;
}
.cell-count {
  font-weight: 600;
  color: #1d2129;
  margin-right: 6px;
}
.cell-pct {
  font-size: 12px;
  color: #86909c;
}
.empty-tip {
  color: #86909c;
  font-size: 13px;
  padding: 12px 0;
}
@media (max-width: 1400px) {
  .cards-5 {
    grid-template-columns: repeat(3, 1fr);
  }
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, put } from '../api/http'
import type { PageResult, Row } from '../api/types'

const STATUSES = ['DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED', 'FINISHED', 'FAILED']
const CHANNEL_LABELS: Record<string, string> = {
  sms: '短信',
  email: '邮件',
  wechat: '微信',
  push: '推送',
  console: '控制台',
}

const campaigns = ref<Row[]>([])
const audiences = ref<Row[]>([])
const templates = ref<Row[]>([])
const channelCodes = ref<string[]>([])
const loading = ref(false)
const view = ref<'table' | 'board'>('table')
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)

/** 投递查看弹窗（触发人员 / 投递日志，共用同一分页数据源）。 */
const deliveryVisible = ref(false)
const deliveryCampaign = ref<Row | null>(null)
const deliveryTab = ref('people')
const deliveryRows = ref<Row[]>([])
const deliveryTotal = ref(0)
const deliveryPage = ref(1)
const deliveryPageSize = 20
const deliveryLoading = ref(false)

/** base64url 无 padding（后端 PageToken 契约），offset = (page-1)*size。 */
function encodeOffset(offset: number): string {
  return btoa(String(offset)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function openDeliveries(row: Row) {
  deliveryCampaign.value = row
  deliveryTab.value = 'people'
  deliveryPage.value = 1
  deliveryVisible.value = true
  loadDeliveries(1)
}

async function loadDeliveries(page: number) {
  if (!deliveryCampaign.value) return
  deliveryLoading.value = true
  try {
    const offset = (page - 1) * deliveryPageSize
    const params: Record<string, string | number> = { limit: deliveryPageSize }
    if (offset > 0) params.pageToken = encodeOffset(offset)
    const p = await get<PageResult<Row>>(`/campaigns/${deliveryCampaign.value.id}/deliveries`, params)
    deliveryRows.value = p.items ?? []
    deliveryTotal.value = p.total ?? 0
    deliveryPage.value = page
  } finally {
    deliveryLoading.value = false
  }
}

function fmtTime(v?: unknown): string {
  return v == null ? '-' : String(v)
}

function channelLabel(code?: unknown) {
  return CHANNEL_LABELS[String(code ?? '')] ?? String(code ?? '-')
}

/** 目标人群展示：audience_snapshot {audience_name, member_count, snapshot_at}；未快照的存量活动显示 '-'。 */
function fmtAudienceSnapshot(snap?: unknown) {
  if (!snap || typeof snap !== 'object') return '-'
  const s = snap as Row
  const name = String(s.audience_name ?? s.name ?? '')
  const count = s.member_count ?? 0
  return `${name}（${count} 人）`
}

const emptyForm = () => ({
  id: null as number | null,
  name: '',
  audience_id: null as number | null,
  template_id: null as number | null,
  channel: '',
  schedule: null as Date | null,
  cron: '',
  template_routing_text: '',
  event_type: '',
  window: '',
})
const form = reactive(emptyForm())

async function load() {
  loading.value = true
  try {
    const [page, aud, tpl, channels] = await Promise.all([
      get<{ items: Row[]; total: number }>('/campaigns'),
      get<{ items: Row[]; total: number }>('/objects/audience', { limit: 200 }),
      get<{ items: Row[]; total: number }>('/objects/template', { limit: 200 }),
      get<string[]>('/channels'),
    ])
    campaigns.value = page.items ?? []
    audiences.value = aud.items ?? []
    templates.value = tpl.items ?? []
    channelCodes.value = channels ?? []
  } finally {
    loading.value = false
  }
}

async function trigger(row: Row) {
  const r = await post<Record<string, unknown>>(`/campaigns/${row.id}/trigger`, {})
  ElMessage.success(`触发完成：${JSON.stringify(r)}`)
  load()
}

async function pause(row: Row) {
  await post(`/campaigns/${row.id}/pause`, {})
  ElMessage.success('已暂停')
  load()
}

function openCreate() {
  Object.assign(form, emptyForm())
  isEdit.value = false
  dialogVisible.value = true
}

function openEdit(row: Row) {
  const rule = (row.trigger_rule ?? {}) as Row
  Object.assign(form, {
    id: row.id as number,
    name: String(row.name ?? ''),
    audience_id: (row.audience_id as number | null) ?? null,
    template_id: (row.template_id as number | null) ?? null,
    channel: String(row.channel ?? ''),
    schedule: row.schedule ? new Date(row.schedule as string) : null,
    cron: String(row.cron ?? ''),
    template_routing_text: row.template_routing ? JSON.stringify(row.template_routing, null, 2) : '',
    event_type: String(rule.event_type ?? ''),
    window: rule.window == null ? '' : String(rule.window),
  })
  isEdit.value = true
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写活动名称')
    return
  }
  if (!form.audience_id) {
    ElMessage.warning('请选择人群')
    return
  }
  if (!form.channel) {
    ElMessage.warning('请选择通道')
    return
  }
  if (!form.template_id) {
    ElMessage.warning('请选择模板')
    return
  }
  let templateRouting: Row[] | null = null
  if (form.template_routing_text.trim()) {
    try {
      const parsed: unknown = JSON.parse(form.template_routing_text)
      if (!Array.isArray(parsed)) {
        throw new Error('需为 JSON 数组')
      }
      templateRouting = parsed as Row[]
    } catch (e) {
      ElMessage.error(`模板路由 JSON 解析失败：${(e as Error).message}`)
      return
    }
  }
  // 触发规则仅回传非空键，避免误清原值（后端按键合并、缺失保留）
  const rule: Row = {}
  if (form.event_type.trim()) rule.event_type = form.event_type.trim()
  if (String(form.window ?? '').trim() !== '') rule.window = String(form.window).trim()
  const body = {
    name: form.name.trim(),
    audienceId: form.audience_id,
    channel: form.channel,
    templateId: form.template_id,
    schedule: form.schedule ? new Date(form.schedule).toISOString() : null,
    cron: form.cron.trim() || null,
    templateRouting: templateRouting,
    triggerRule: rule,
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await put(`/campaigns/${form.id}`, body)
      ElMessage.success('已保存')
    } else {
      await post('/campaigns', body)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const byStatus = computed<Record<string, Row[]>>(() => {
  const m: Record<string, Row[]> = {}
  for (const s of STATUSES) m[s] = []
  for (const c of campaigns.value) {
    const s = String(c.status ?? '')
    ;(m[STATUSES.includes(s) ? s : 'DRAFT'] ?? []).push(c)
  }
  return m
})

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>运营活动（人群 + 触发规则）</span>
        <div style="display: flex; gap: 10px; align-items: center">
          <el-radio-group v-model="view" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="board">看板</el-radio-button>
          </el-radio-group>
          <el-button type="primary" size="small" @click="openCreate">新建</el-button>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table v-if="view === 'table'" v-loading="loading" :data="campaigns" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column prop="channel" label="通道" width="90" />
      <el-table-column prop="trigger_rule" label="触发规则" min-width="200">
        <template #default="{ row }">{{ JSON.stringify(row.trigger_rule ?? {}) }}</template>
      </el-table-column>
      <el-table-column prop="template_routing" label="模板路由" min-width="220">
        <template #default="{ row }">
          <span v-if="row.template_routing && row.template_routing.length">{{ JSON.stringify(row.template_routing) }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="目标人群" min-width="170">
        <template #default="{ row }">
          <span>{{ fmtAudienceSnapshot(row.audience_snapshot) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="370" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="trigger(row)">触发</el-button>
          <el-button size="small" type="warning" @click="pause(row)">暂停</el-button>
          <el-button size="small" @click="openDeliveries(row)">查看</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-else v-loading="loading" class="board">
      <div v-for="s in STATUSES" :key="s" class="board-col">
        <div class="board-col-title">{{ s }}（{{ (byStatus[s] ?? []).length }}）</div>
        <div class="board-cards">
          <el-card v-for="row in byStatus[s] ?? []" :key="row.id" shadow="hover" class="board-card">
            <div class="board-card-title" @click="openEdit(row)">{{ row.name }}</div>
            <div class="board-card-meta">
              <div>通道：{{ channelLabel(row.channel) }}</div>
              <div>人群：{{ fmtAudienceSnapshot(row.audience_snapshot) }}</div>
              <div class="rule">规则：{{ JSON.stringify(row.trigger_rule ?? {}) }}</div>
            </div>
            <div class="board-card-actions">
              <el-button size="small" type="primary" @click="trigger(row)">触发</el-button>
              <el-button size="small" @click="openDeliveries(row)">查看</el-button>
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" type="warning" @click="pause(row)">暂停</el-button>
            </div>
          </el-card>
        </div>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="deliveryVisible" :title="`投递查看：${deliveryCampaign?.name ?? ''}`" width="960px" destroy-on-close>
    <el-tabs v-model="deliveryTab">
      <el-tab-pane label="触发人员" name="people">
        <el-table v-loading="deliveryLoading" :data="deliveryRows" size="small" stripe>
          <el-table-column prop="customer_id" label="客户 ID" width="90" />
          <el-table-column prop="customer_external_id" label="外部 ID" width="120" show-overflow-tooltip />
          <el-table-column prop="customer_name" label="姓名" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.customer_name == null || row.customer_name === '' ? '-' : row.customer_name }}</template>
          </el-table-column>
          <el-table-column prop="customer_phone" label="手机号" width="140">
            <template #default="{ row }">{{ row.customer_phone ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="customer_email" label="邮箱" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.customer_email ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="customer_status" label="客户状态" width="100">
            <template #default="{ row }">{{ row.customer_status ?? '-' }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="投递日志" name="log">
        <el-table v-loading="deliveryLoading" :data="deliveryRows" size="small" stripe>
          <el-table-column prop="id" label="投递 ID" width="90" />
          <el-table-column prop="customer_id" label="客户 ID" width="90" />
          <el-table-column prop="channel" label="通道" width="80" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="attempt" label="尝试" width="70" />
          <el-table-column prop="error" label="错误" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="created_at" label="创建时间" width="180">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column prop="updated_at" label="更新时间" width="180">
            <template #default="{ row }">{{ fmtTime(row.updated_at) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <div style="display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 12px">
      <span style="color: #909399; font-size: 13px">共 {{ deliveryTotal }} 条（按触发时间倒序）</span>
      <el-pagination
        small
        background
        layout="prev, pager, next"
        :total="deliveryTotal"
        :current-page="deliveryPage"
        :page-size="deliveryPageSize"
        @current-change="loadDeliveries"
      />
    </div>
  </el-dialog>

  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑活动' : '新建活动'" width="640px" destroy-on-close>
    <el-form label-width="110px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" placeholder="活动名称" />
      </el-form-item>
      <el-form-item label="人群" required>
        <el-select v-model="form.audience_id" filterable placeholder="选择人群" style="width: 100%">
          <el-option v-for="a in audiences" :key="a.id" :value="a.id" :label="`${a.name}（ID ${a.id}）`" />
        </el-select>
        <div style="color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px">创建时按所选人群固化发送范围（快照）；之后修改人群规则不影响本活动。</div>
      </el-form-item>
      <el-form-item label="模板" required>
        <el-select v-model="form.template_id" filterable placeholder="选择模板" style="width: 100%">
          <el-option v-for="t in templates" :key="t.id" :value="t.id" :label="`${t.title}（${channelLabel(t.channel)}）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="通道" required>
        <el-select v-model="form.channel" placeholder="选择通道" style="width: 100%">
          <el-option v-for="c in channelCodes" :key="c" :value="c" :label="`${channelLabel(c)}（${c}）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="一次性时间">
        <el-date-picker v-model="form.schedule" type="datetime" placeholder="可选，留空则不排期" style="width: 100%" />
      </el-form-item>
      <el-form-item label="Cron">
        <el-input v-model="form.cron" placeholder="可选，如 0 0 20 * * ?" />
      </el-form-item>
      <el-form-item label="模板路由">
        <el-input v-model="form.template_routing_text" type="textarea" :rows="5"
                  placeholder='[{"event_type":"order_placed","conditions":[{"attr":"new_customer","op":"eq","value":true}],"template_id":2}] — 顺序匹配首条命中，未命中回退主模板；留空=保留现有，填 [] 清空' />
      </el-form-item>
      <el-form-item label="触发规则">
        <div style="display: flex; flex-direction: column; gap: 8px; width: 100%">
          <el-input v-model="form.event_type" placeholder="事件类型，如 order_placed（留空则不事件触发）" />
          <el-input v-model="form.window" placeholder="时间窗，如 1d（预留，可留空）" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.board {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}
.board-col {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px;
  min-height: 120px;
}
.board-col-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}
.board-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.board-card {
  --el-card-padding: 10px;
}
.board-card-title {
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  margin-bottom: 8px;
  color: #409eff;
}
.board-card-meta {
  font-size: 12px;
  color: #606266;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.board-card-meta .rule {
  word-break: break-all;
}
.form-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
.board-card-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
</style>
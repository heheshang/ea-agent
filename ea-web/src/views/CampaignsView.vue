<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, put } from '../api/http'
import type { Row } from '../api/types'

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

function channelLabel(code?: unknown) {
  return CHANNEL_LABELS[String(code ?? '')] ?? String(code ?? '-')
}

/** 解析后端冷静期文本（归一 ISO-8601 或宽松 1h/30m/90s/纯数字秒）为数值+单位；无法结构化（如 PT1M30S）返回 null，交由原文兜底。 */
function parseCooldown(raw: string): { value: number; unit: 's' | 'm' | 'h' | 'd' } | null {
  if (!raw) return null
  const s = raw.trim()
  let m = /^(\d+)([smhd])$/i.exec(s)
  if (m) return { value: Number(m[1]), unit: m[2].toLowerCase() as 's' | 'm' | 'h' | 'd' }
  m = /^(\d+)$/.exec(s)
  if (m) return { value: Number(m[1]), unit: 's' }
  m = /^PT(\d+)H$/i.exec(s)
  if (m) return { value: Number(m[1]), unit: 'h' }
  m = /^PT(\d+)M$/i.exec(s)
  if (m) return { value: Number(m[1]), unit: 'm' }
  m = /^PT(\d+)S$/i.exec(s)
  if (m) return { value: Number(m[1]), unit: 's' }
  m = /^P(\d+)D$/i.exec(s)
  if (m) return { value: Number(m[1]), unit: 'd' }
  return null
}

const emptyForm = () => ({
  id: null as number | null,
  name: '',
  audience_id: null as number | null,
  template_id: null as number | null,
  channel: '',
  schedule: null as Date | null,
  cron: '',
  gray_ratio: 100,
  ab_mode: 'NONE',
  ab_split: null as number | null,
  ab_variants_text: '',
  template_routing_text: '',
  event_type: '',
  window: '',
  cooldown_value: null as number | null,
  cooldown_unit: 'h' as 's' | 'm' | 'h' | 'd',
  cooldown_raw: '',
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

async function abReport(row: Row) {
  const r = await get<Record<string, unknown>>(`/campaigns/${row.id}/ab-report`)
  ElMessage.info(`AB 报告：${JSON.stringify(r)}`)
}

function openCreate() {
  Object.assign(form, emptyForm())
  isEdit.value = false
  dialogVisible.value = true
}

function openEdit(row: Row) {
  const rule = (row.trigger_rule ?? {}) as Row
  const cdRaw = rule.cooldown == null ? '' : String(rule.cooldown)
  const cd = parseCooldown(cdRaw)
  if (cdRaw && !cd) {
    ElMessage.warning('冷静期原文无法结构化（如组合 ISO 时长），已保留原文可手动修改')
  }
  Object.assign(form, {
    id: row.id as number,
    name: String(row.name ?? ''),
    audience_id: (row.audience_id as number | null) ?? null,
    template_id: (row.template_id as number | null) ?? null,
    channel: String(row.channel ?? ''),
    schedule: row.schedule ? new Date(row.schedule as string) : null,
    cron: String(row.cron ?? ''),
    gray_ratio: (row.gray_ratio as number) ?? 100,
    ab_mode: String(row.ab_mode ?? 'NONE'),
    ab_split: (row.ab_split as number | null) ?? null,
    ab_variants_text: row.ab_variants ? JSON.stringify(row.ab_variants, null, 2) : '',
    template_routing_text: row.template_routing ? JSON.stringify(row.template_routing, null, 2) : '',
    event_type: String(rule.event_type ?? ''),
    window: rule.window == null ? '' : String(rule.window),
    cooldown_value: cd ? cd.value : null,
    cooldown_unit: cd ? cd.unit : 'h',
    cooldown_raw: cd ? '' : cdRaw,
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
  if (form.gray_ratio < 0 || form.gray_ratio > 100) {
    ElMessage.warning('灰度需在 0-100 之间')
    return
  }
  let abVariants: Row[] | null = null
  if (form.ab_variants_text.trim()) {
    try {
      const parsed: unknown = JSON.parse(form.ab_variants_text)
      if (!Array.isArray(parsed)) {
        throw new Error('需为 JSON 数组')
      }
      abVariants = parsed as Row[]
    } catch (e) {
      ElMessage.error(`AB 变体 JSON 解析失败：${(e as Error).message}`)
      return
    }
  }
  if (form.ab_mode === 'AB') {
    if (form.ab_split == null || form.ab_split < 1 || form.ab_split > 99) {
      ElMessage.warning('AB 模式需配置切分比例（1-99）')
      return
    }
    if (!abVariants || abVariants.length === 0) {
      ElMessage.warning('AB 模式需至少 1 个变体')
      return
    }
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
  const cdRaw = form.cooldown_raw.trim()
  if (cdRaw) {
    rule.cooldown = cdRaw
  } else if (form.cooldown_value != null && form.cooldown_value > 0) {
    rule.cooldown = `${form.cooldown_value}${form.cooldown_unit}`
  }
  const body = {
    name: form.name.trim(),
    audienceId: form.audience_id,
    channel: form.channel,
    templateId: form.template_id,
    schedule: form.schedule ? new Date(form.schedule).toISOString() : null,
    cron: form.cron.trim() || null,
    grayRatio: form.gray_ratio,
    abMode: form.ab_mode,
    abSplit: form.ab_split,
    abVariants: abVariants,
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
        <span>运营活动（人群 + 灰度/AB + 触发规则）</span>
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
      <el-table-column prop="gray_ratio" label="灰度%" width="80" />
      <el-table-column prop="ab_mode" label="AB 模式" width="90" />
      <el-table-column prop="trigger_rule" label="触发规则" min-width="200">
        <template #default="{ row }">{{ JSON.stringify(row.trigger_rule ?? {}) }}</template>
      </el-table-column>
      <el-table-column prop="template_routing" label="模板路由" min-width="220">
        <template #default="{ row }">
          <span v-if="row.template_routing && row.template_routing.length">{{ JSON.stringify(row.template_routing) }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="trigger(row)">触发</el-button>
          <el-button size="small" type="warning" @click="pause(row)">暂停</el-button>
          <el-button size="small" @click="abReport(row)">AB</el-button>
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
              <div>灰度：{{ row.gray_ratio ?? 100 }}%</div>
              <div>AB：{{ row.ab_mode ?? 'NONE' }}</div>
              <div class="rule">规则：{{ JSON.stringify(row.trigger_rule ?? {}) }}</div>
            </div>
            <div class="board-card-actions">
              <el-button size="small" type="primary" @click="trigger(row)">触发</el-button>
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" type="warning" @click="pause(row)">暂停</el-button>
            </div>
          </el-card>
        </div>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑活动' : '新建活动'" width="640px" destroy-on-close>
    <el-form label-width="110px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" placeholder="活动名称" />
      </el-form-item>
      <el-form-item label="人群" required>
        <el-select v-model="form.audience_id" filterable placeholder="选择人群" style="width: 100%">
          <el-option v-for="a in audiences" :key="a.id" :value="a.id" :label="`${a.name}（ID ${a.id}）`" />
        </el-select>
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
      <el-form-item label="灰度 %">
        <el-input-number v-model="form.gray_ratio" :min="0" :max="100" />
      </el-form-item>
      <el-form-item label="AB 模式">
        <el-select v-model="form.ab_mode" style="width: 180px">
          <el-option value="NONE" label="无" />
          <el-option value="AB" label="AB" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.ab_mode === 'AB'" label="AB 切分">
        <el-input-number v-model="form.ab_split" :min="1" :max="99" placeholder="变体总占比 1-99" />
      </el-form-item>
      <el-form-item v-if="form.ab_mode === 'AB'" label="AB 变体">
        <el-input v-model="form.ab_variants_text" type="textarea" :rows="3" placeholder='[{"name":"A","percent":30}] — percent 合计不超过 ab_split' />
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
      <el-form-item label="冷静期">
        <div style="display: flex; flex-direction: column; gap: 8px; width: 100%">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input-number v-model="form.cooldown_value" :min="0" :step="1" style="width: 150px" placeholder="数值" />
            <el-select v-model="form.cooldown_unit" style="width: 110px">
              <el-option value="s" label="秒" />
              <el-option value="m" label="分钟" />
              <el-option value="h" label="小时" />
              <el-option value="d" label="天" />
            </el-select>
            <el-input v-model="form.cooldown_raw" placeholder="ISO 原文如 PT1M30S（可选）" style="flex: 1" />
          </div>
          <div class="form-hint">同客户同活动在冷静期内仅发送一次；数值+单位留空且原文为空 = 不配置（默认 1 小时）</div>
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
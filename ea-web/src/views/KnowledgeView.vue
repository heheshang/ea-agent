<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, post, put } from '../api/http'
import type { KnowledgeEntry, KnowledgeHit, PageResult } from '../api/types'

/**
 * 知识库管理：租户维度知识条目 CRUD + 启停 + 试检索。
 * 检索预览与 Agent 对话注入同源（后端确定性打分），top 命中含得分。
 * V14 本体化：类型化记录（决策/约束/规则/经验/理由/事实/反模式）+ 生命周期（active/superseded/obsolete）
 * + 取代链（supersedesId，设置时旧条目自动置 superseded）；管理页按类型/生命周期过滤、查看取代链。
 */

const RECORD_TYPES: { value: string; label: string }[] = [
  { value: 'rule', label: '规则' },
  { value: 'constraint', label: '约束' },
  { value: 'decision', label: '决策' },
  { value: 'rationale', label: '理由' },
  { value: 'lesson', label: '经验' },
  { value: 'fact', label: '事实' },
  { value: 'anti_pattern', label: '反模式' },
]

const LIFECYCLES: { value: string; label: string }[] = [
  { value: 'active', label: '现行' },
  { value: 'superseded', label: '已取代' },
  { value: 'obsolete', label: '废弃' },
]

const LIFECYCLE_TAG_TYPE: Record<string, string> = {
  active: 'success',
  superseded: 'warning',
  obsolete: 'info',
}

function typeLabel(v?: string): string {
  return RECORD_TYPES.find(t => t.value === v)?.label ?? '规则'
}

function lifecycleLabel(v?: string): string {
  return LIFECYCLES.find(t => t.value === v)?.label ?? '-'
}

const loading = ref(false)
const rows = ref<KnowledgeEntry[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const typeFilter = ref('')
const lifecycleFilter = ref('')

const dialogOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = ref({
  title: '',
  content: '',
  tags: [] as string[],
  enabled: true,
  recordType: 'rule',
  lifecycle: 'active',
  supersedesId: null as number | null,
})

/** 取代候选：同租户现行条目（supersedesId 下拉），dv 加载一次。 */
const supersedeCandidates = ref<KnowledgeEntry[]>([])

const searchQ = ref('')
const searchTopK = ref(3)
const searchInactive = ref(false)
const searching = ref(false)
const hits = ref<KnowledgeHit[]>([])

const traceOpen = ref(false)
const traceTitle = ref('')
const traceChain = ref<KnowledgeEntry[]>([])

async function load() {
  loading.value = true
  try {
    const res = await get<PageResult<KnowledgeEntry>>('/knowledge', {
      keyword: keyword.value || undefined,
      recordType: typeFilter.value || undefined,
      lifecycle: lifecycleFilter.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = res.items ?? []
    total.value = res.total ?? 0
  } finally {
    loading.value = false
  }
}

/** 取代候选：同租户全部条目（含被取代的也能被选中当目标？不能——后端校验目标须现行；只列 active）。 */
async function loadSupersedeCandidates() {
  const res = await get<PageResult<KnowledgeEntry>>('/knowledge', {
    lifecycle: 'active',
    page: 1,
    size: 100,
  })
  supersedeCandidates.value = res.items ?? []
}

function openCreate() {
  editingId.value = null
  form.value = { title: '', content: '', tags: [], enabled: true, recordType: 'rule', lifecycle: 'active', supersedesId: null }
  dialogOpen.value = true
}

function openEdit(row: KnowledgeEntry) {
  editingId.value = row.id
  form.value = {
    title: row.title,
    content: row.content,
    tags: [...(row.tags ?? [])],
    enabled: row.enabled,
    recordType: row.recordType ?? 'rule',
    lifecycle: row.lifecycle ?? 'active',
    supersedesId: row.supersedesId ?? null,
  }
  dialogOpen.value = true
}

async function save() {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请填写内容')
    return
  }
  if (form.value.supersedesId !== null && form.value.supersedesId === editingId.value) {
    ElMessage.warning('不能取代自身')
    return
  }
  if (form.value.lifecycle === 'superseded' && form.value.supersedesId === null && editingId.value !== null) {
    // 编辑存量条目标为「已取代」但未选取代者：允许（后端不强制），提示语义
    ElMessage.info('标记「已取代」建议同时选择取代条目，便于追踪链')
  }
  saving.value = true
  try {
    const payload = {
      title: form.value.title,
      content: form.value.content,
      tags: form.value.tags,
      enabled: form.value.enabled,
      recordType: form.value.recordType,
      lifecycle: form.value.lifecycle,
      // el-select clearable 清空后值为 undefined，归一为 null（null = 后端不修改；清除取代关系需后续显式字段）
      supersedesId: form.value.supersedesId ?? null,
    }
    if (editingId.value === null) {
      await post<KnowledgeEntry>('/knowledge', payload)
      ElMessage.success('已创建')
    } else {
      // 编辑：后端按传入覆盖、缺失保留，tags 不传保留原值
      await put<KnowledgeEntry>(`/knowledge/${editingId.value}`, payload)
      ElMessage.success('已保存')
    }
    dialogOpen.value = false
    await load()
    await loadSupersedeCandidates()
  } finally {
    saving.value = false
  }
}

/** 启停切换（仅传 enabled，其余字段后端保留）。 */
async function toggleEnabled(row: KnowledgeEntry) {
  await put<KnowledgeEntry>(`/knowledge/${row.id}`, { enabled: row.enabled })
  ElMessage.success(row.enabled ? '已启用' : '已停用')
}

async function remove(row: KnowledgeEntry) {
  await ElMessageBox.confirm(
    `确认删除知识条目「${row.title}」？删除后不可恢复；若其他条目取代了它，取代引用将自动清空。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await del<void>(`/knowledge/${row.id}`)
  ElMessage.success('已删除')
  await load()
  await loadSupersedeCandidates()
}

async function search() {
  if (!searchQ.value.trim()) {
    ElMessage.warning('请输入检索词')
    return
  }
  searching.value = true
  try {
    hits.value = await get<KnowledgeHit[]>('/knowledge/search', {
      q: searchQ.value,
      top_k: searchTopK.value,
      active_only: !searchInactive.value,
    })
  } finally {
    searching.value = false
  }
}

/** 取代链弹窗（最旧 → 最新）。 */
async function showTrace(row: KnowledgeEntry) {
  traceTitle.value = row.title
  traceChain.value = []
  traceOpen.value = true
  traceChain.value = await get<KnowledgeEntry[]>(`/knowledge/${row.id}/trace`)
}

onMounted(() => {
  load()
  loadSupersedeCandidates()
})
</script>

<template>
  <div v-loading="loading" class="kb-page">
    <div class="page-head">
      <div>
        <div class="page-title">📚 知识库</div>
        <div class="page-sub">租户维度的业务规则与事实条目，对话时按相关度注入 Agent 上下文（无命中不注入）</div>
      </div>
      <el-button type="primary" @click="openCreate">＋ 新建条目</el-button>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" clearable placeholder="按标题/内容/标签关键字过滤" class="kw" @keyup.enter="page = 1; load()" />
      <el-select v-model="typeFilter" clearable placeholder="类型" style="width: 120px" @change="page = 1; load()">
        <el-option v-for="t in RECORD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="lifecycleFilter" clearable placeholder="生命周期" style="width: 120px" @change="page = 1; load()">
        <el-option v-for="t in LIFECYCLES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-button type="primary" plain @click="page = 1; load()">查询</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <el-table :data="rows" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ typeLabel(row.recordType) }}</template>
        </el-table-column>
        <el-table-column label="生命周期" width="100">
          <template #default="{ row }">
            <el-tag :type="LIFECYCLE_TAG_TYPE[row.lifecycle ?? 'active'] ?? 'info'" size="small">
              {{ lifecycleLabel(row.lifecycle) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="取代" width="110">
          <template #default="{ row }">
            <span v-if="row.supersedesId" class="muted">取代 #{{ row.supersedesId }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="t in row.tags ?? []" :key="t" size="small" class="tag">{{ t }}</el-tag>
            <span v-if="!row.tags?.length" class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v: boolean | string | number) => { row.enabled = Boolean(v); toggleEnabled(row) }" />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170">
          <template #default="{ row }">{{ row.updatedAt?.replace('T', ' ').slice(0, 16) ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="showTrace(row)">取代链</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="size"
          @current-change="(p: number) => { page = p; load() }"
        />
      </div>
    </el-card>

    <!-- 试检索：与 Agent 注入同源的确定性打分预览 -->
    <el-card shadow="never" class="panel search-panel">
      <template #header>🔍 试检索（与对话注入同源打分）</template>
      <div class="search-bar">
        <el-input v-model="searchQ" clearable placeholder="输入与对话相近的提问，如：触达前要检查什么" class="kw" @keyup.enter="search" />
        <el-input-number v-model="searchTopK" :min="1" :max="10" class="topk" />
        <el-checkbox v-model="searchInactive" style="flex-shrink: 0">含已取代/废弃</el-checkbox>
        <el-button type="primary" :loading="searching" @click="search">检索</el-button>
      </div>
      <el-table v-if="hits.length" :data="hits" size="small">
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ typeLabel(row.recordType) }}</template>
        </el-table-column>
        <el-table-column label="生命周期" width="90">
          <template #default="{ row }">
            <el-tag :type="LIFECYCLE_TAG_TYPE[row.lifecycle ?? 'active'] ?? 'info'" size="small">
              {{ lifecycleLabel(row.lifecycle) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="t in row.tags ?? []" :key="t" size="small" class="tag">{{ t }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="相关度得分" width="110">
          <template #default="{ row }">
            <span class="score" :class="{ hot: row.score >= 4 }">{{ row.score }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-else-if="searching" class="empty-tip">检索中…</div>
      <div v-else class="empty-tip">输入检索词后点「检索」预览命中条目</div>
    </el-card>

    <el-dialog v-model="dialogOpen" :title="editingId === null ? '新建知识条目' : '编辑知识条目'" width="640px">
      <el-form label-width="72px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="256" show-word-limit placeholder="如：触达退订规范" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="业务规则 / 事实描述，对话检索时按相关度注入上下文（单条超 500 字截断）" />
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tags" multiple filterable allow-create default-first-option placeholder="输入后回车添加标签">
            <el-option v-for="t in form.tags" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.recordType" placeholder="记录类别">
            <el-option v-for="t in RECORD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <div class="field-tip">约束/规则进入上下文时高优先级；反模式/经验用于避坑提示</div>
        </el-form-item>
        <el-form-item label="生命周期">
          <el-select v-model="form.lifecycle" placeholder="生命周期">
            <el-option v-for="t in LIFECYCLES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <div class="field-tip">现行=注入对话；已取代/废弃=不注入。设置「取代条目」时旧条目自动置「已取代」</div>
        </el-form-item>
        <el-form-item label="取代条目">
          <el-select
            v-model="form.supersedesId"
            clearable
            filterable
            placeholder="选择被本条取代的旧条目（可选）"
            style="width: 100%"
          >
            <el-option
              v-for="c in supersedeCandidates"
              :key="c.id"
              :label="`#${c.id} ${c.title}`"
              :value="c.id"
              :disabled="c.id === editingId"
            />
          </el-select>
          <div class="field-tip">选择后旧条目生命周期自动置「已取代」，不再注入；可用「取代链」查看版本演进</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  <el-dialog v-model="traceOpen" :title="`取代链：${traceTitle}`" width="560px">
      <el-timeline v-if="traceChain.length">
        <el-timeline-item
          v-for="(c, idx) in traceChain"
          :key="c.id"
          :type="c.lifecycle === 'active' ? 'primary' : c.lifecycle === 'superseded' ? 'warning' : 'info'"
        >
          <div class="trace-version">
            <span class="trace-id">#{{ c.id }}</span>
            <el-tag :type="LIFECYCLE_TAG_TYPE[c.lifecycle ?? 'active'] ?? 'info'" size="small">{{ lifecycleLabel(c.lifecycle) }}</el-tag>
            <el-tag size="small" class="tag">{{ typeLabel(c.recordType) }}</el-tag>
            <template v-if="idx === traceChain.length - 1 && c.lifecycle === 'active'">
              <el-tag size="small" type="success" class="tag">现行</el-tag>
            </template>
          </div>
          <div class="trace-title">{{ c.title }}</div>
          <div v-if="c.content" class="trace-content">{{ c.content.slice(0, 120) }}{{ c.content.length > 120 ? '…' : '' }}</div>
        </el-timeline-item>
      </el-timeline>
      <div v-else class="empty-tip">暂无取代链</div>
      <template #footer>
        <el-button @click="traceOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-page {
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
  font-size: 12px;
  color: #86909c;
  margin-top: 4px;
}
.toolbar {
  display: flex;
  gap: 8px;
}
.kw {
  width: 320px;
}
.panel {
  border-radius: 12px;
}
.search-panel {
  margin-top: 4px;
}
.search-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.topk {
  width: 110px;
}
.tag {
  margin-right: 4px;
}
.muted {
  color: #c9cdd4;
}
.score {
  font-weight: 600;
  color: #4e5969;
}
.score.hot {
  color: #3370ff;
}
.field-tip {
  font-size: 12px;
  color: #86909c;
  line-height: 1.6;
  width: 100%;
}
.trace-version {
  display: flex;
  align-items: center;
  gap: 6px;
}
.trace-id {
  font-weight: 600;
  color: #4e5969;
}
.trace-title {
  margin-top: 4px;
  font-weight: 600;
  color: #1d2129;
}
.trace-content {
  margin-top: 2px;
  color: #86909c;
  font-size: 13px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.empty-tip {
  color: #86909c;
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}
</style>
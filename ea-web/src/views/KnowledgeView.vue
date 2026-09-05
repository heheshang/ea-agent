<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, post, put } from '../api/http'
import type { KnowledgeEntry, KnowledgeHit, PageResult } from '../api/types'

/**
 * 知识库管理：租户维度知识条目 CRUD + 启停 + 试检索。
 * 检索预览与 Agent 对话注入同源（后端确定性打分），top 命中含得分。
 */

const loading = ref(false)
const rows = ref<KnowledgeEntry[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')

const dialogOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ title: '', content: '', tags: [] as string[], enabled: true })

const searchQ = ref('')
const searchTopK = ref(3)
const searching = ref(false)
const hits = ref<KnowledgeHit[]>([])

async function load() {
  loading.value = true
  try {
    const res = await get<PageResult<KnowledgeEntry>>('/knowledge', {
      keyword: keyword.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = res.items ?? []
    total.value = res.total ?? 0
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = { title: '', content: '', tags: [], enabled: true }
  dialogOpen.value = true
}

function openEdit(row: KnowledgeEntry) {
  editingId.value = row.id
  form.value = { title: row.title, content: row.content, tags: [...(row.tags ?? [])], enabled: row.enabled }
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
  saving.value = true
  try {
    if (editingId.value === null) {
      await post<KnowledgeEntry>('/knowledge', { ...form.value })
      ElMessage.success('已创建')
    } else {
      // 编辑：后端按传入覆盖、缺失保留，tags 不传保留原值
      await put<KnowledgeEntry>(`/knowledge/${editingId.value}`, { ...form.value })
      ElMessage.success('已保存')
    }
    dialogOpen.value = false
    await load()
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
  await ElMessageBox.confirm(`确认删除知识条目「${row.title}」？删除后不可恢复。`, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await del<void>(`/knowledge/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

async function search() {
  if (!searchQ.value.trim()) {
    ElMessage.warning('请输入检索词')
    return
  }
  searching.value = true
  try {
    hits.value = await get<KnowledgeHit[]>('/knowledge/search', { q: searchQ.value, top_k: searchTopK.value })
  } finally {
    searching.value = false
  }
}

onMounted(load)
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
      <el-button type="primary" plain @click="page = 1; load()">查询</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <el-table :data="rows" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
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
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
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
        <el-button type="primary" :loading="searching" @click="search">检索</el-button>
      </div>
      <el-table v-if="hits.length" :data="hits" size="small">
        <el-table-column prop="title" label="标题" min-width="200" />
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
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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
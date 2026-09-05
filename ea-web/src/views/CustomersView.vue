<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, put } from '../api/http'
import type { PageResult, Row } from '../api/types'

const rows = ref<Row[]>([])
const total = ref(0)
const loading = ref(false)

// ---- 画像编辑 ----
const dialog = ref(false)
const saving = ref(false)
const editing = ref<Row | null>(null)
const tags = ref<string[]>([])
const attrRows = ref<{ key: string; value: string }[]>([])
/** 常用属性快捷键（男女/生日/爱好等；值仍自由编辑，支持文本或 JSON）。 */
const PRESETS = ['gender', 'birthday', 'hobbies', 'city', 'occupation', 'level', 'vip_since']

function tagsOf(row: Row): string[] {
  return Array.isArray(row.tags) ? (row.tags as string[]) : []
}

function attrsOf(row: Row): Record<string, unknown> {
  const v = row.attributes
  return v && typeof v === 'object' ? (v as Record<string, unknown>) : {}
}

function fmtValue(v: unknown): string {
  if (v === null || v === undefined) return ''
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')

/** base64url 无 padding（后端 PageToken 契约）。 */
function encodeOffset(offset: number): string {
  return btoa(String(offset)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function load() {
  loading.value = true
  try {
    const offset = (page.value - 1) * pageSize.value
    const params: Record<string, string | number> = {
      limit: pageSize.value,
      // 固定 -id 排序：created_at 同秒批量导入时排序不定，翻页会重复/遗漏
      sort: '-id',
    }
    if (keyword.value) params.keyword = keyword.value.trim()
    if (offset > 0) params.pageToken = encodeOffset(offset)
    const p = await get<PageResult<Row>>('/objects/customer', params)
    rows.value = p.items
    total.value = p.total
  } finally {
    loading.value = false
  }
}

function onPage(p: number) {
  page.value = p
  load()
}

/** 新关键字搜索回到第 1 页。 */
function onSearch() {
  page.value = 1
  load()
}

function openEdit(row: Row) {
  editing.value = row
  tags.value = [...tagsOf(row)]
  attrRows.value = Object.entries(attrsOf(row)).map(([k, v]) => ({ key: k, value: fmtValue(v) }))
  dialog.value = true
}

function addAttr() {
  attrRows.value.push({ key: '', value: '' })
}

function removeAttr(i: number) {
  attrRows.value.splice(i, 1)
}

function usePreset(key: string) {
  if (attrRows.value.some((r) => r.key === key)) {
    ElMessage.info(`属性 ${key} 已存在`)
    return
  }
  attrRows.value.push({ key, value: '' })
}

/** 值文本按 JSON 解析（数组/数字/布尔/对象），解析失败按字符串存。 */
function parseValue(raw: string): unknown {
  const s = raw.trim()
  if (s === '') return ''
  try {
    return JSON.parse(s)
  } catch {
    return s
  }
}

async function save() {
  if (!editing.value) return
  saving.value = true
  try {
    const attributes: Record<string, unknown> = {}
    for (const r of attrRows.value) {
      const k = r.key.trim()
      if (!k) continue
      attributes[k] = parseValue(r.value)
    }
    await put(`/objects/customer/${editing.value.id}`, {
      attributes,
      tags: tags.value,
    })
    ElMessage.success('客户画像已更新（属性/标签整表替换）')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>客户对象（/api/objects/customer，租户隔离）</span>
        <div style="display: flex; gap: 8px">
          <el-input
            v-model="keyword"
            placeholder="姓名 / 手机 / 邮箱 / 外部 ID"
            clearable
            style="width: 240px"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-button type="primary" size="small" :loading="loading" @click="onSearch">查询</el-button>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column prop="email" label="邮箱" width="200" />
      <el-table-column label="标签" min-width="160">
        <template #default="{ row }">
          <el-tag v-for="t in tagsOf(row)" :key="t" size="small" style="margin-right: 4px">{{ t }}</el-tag>
          <span v-if="tagsOf(row).length === 0" class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="属性" min-width="240">
        <template #default="{ row }">
          <div v-if="Object.keys(attrsOf(row)).length" class="attr-cell">
            <span v-for="(v, k) in attrsOf(row)" :key="k" class="attr-item">{{ k }}: {{ fmtValue(v) }}</span>
          </div>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑画像</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 10px">
      <span class="total">共 {{ total }} 条</span>
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="pageSize"
        @current-change="onPage"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialog" title="编辑客户画像" width="580">
    <el-form label-width="70px">
      <el-form-item label="标签">
        <el-select v-model="tags" multiple filterable allow-create default-first-option placeholder="输入后回车添加标签" style="width: 100%">
          <el-option v-for="t in tags" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="属性">
        <div class="presets">
          <span class="preset-hint">常用：</span>
          <el-tag v-for="p in PRESETS" :key="p" size="small" class="preset" @click="usePreset(p)">{{ p }}</el-tag>
        </div>
        <div v-for="(r, i) in attrRows" :key="i" class="attr-row">
          <el-input v-model="r.key" placeholder="属性名" style="width: 150px" />
          <el-input v-model="r.value" placeholder="值：文本，或 JSON（数组/数字/布尔）" style="flex: 1" />
          <el-button size="small" plain @click="removeAttr(i)">删除</el-button>
        </div>
        <el-button size="small" type="primary" plain @click="addAttr">添加属性</el-button>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.total {
  margin-top: 10px;
  color: #909399;
  font-size: 13px;
}
.muted {
  color: #c0c4cc;
}
.attr-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.attr-item {
  background: #f4f4f5;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}
.presets {
  margin-bottom: 8px;
}
.preset-hint {
  color: #909399;
  font-size: 12px;
  margin-right: 6px;
}
.preset {
  margin-right: 6px;
  cursor: pointer;
}
.attr-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
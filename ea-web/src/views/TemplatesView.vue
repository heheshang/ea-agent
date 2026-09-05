<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import {
  approveTemplate, createTemplate, deleteTemplate, listTemplates, rejectTemplate, submitTemplate, updateTemplate,
} from '../api/templates'
import type { Template } from '../api/types'

const auth = useAuthStore()
const CHANNEL_LABELS: Record<string, string> = {
  sms: '短信',
  email: '邮件',
  wechat: '微信',
  push: '推送',
  console: '控制台',
}

const templates = ref<Template[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const filter = ref('')

const emptyForm = () => ({ id: null as number | null, title: '', channel: 'email', content: '' })
const form = reactive(emptyForm())

function channelLabel(code?: string) {
  return CHANNEL_LABELS[String(code ?? '')] ?? String(code ?? '-')
}

function statusTag(status?: string) {
  switch (status) {
    case 'APPROVED': return 'success'
    case 'PENDING': return 'warning'
    case 'REJECTED': return 'danger'
    default: return 'info'
  }
}

async function load() {
  loading.value = true
  try {
    templates.value = await listTemplates()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  isEdit.value = false
  dialogVisible.value = true
}

function openEdit(row: Template) {
  Object.assign(form, {
    id: row.id,
    title: row.title ?? '',
    channel: row.channel ?? 'email',
    content: row.content ?? '',
  })
  isEdit.value = true
  dialogVisible.value = true
}

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写模板标题')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请填写模板内容')
    return
  }
  const body = { title: form.title.trim(), channel: form.channel, content: form.content }
  saving.value = true
  try {
    if (isEdit.value) {
      await updateTemplate(form.id!, body)
      ElMessage.success('已保存')
    } else {
      await createTemplate(body)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function submit(row: Template) {
  await submitTemplate(row.id)
  ElMessage.success('已提交审核')
  load()
}

async function approve(row: Template) {
  await approveTemplate(row.id)
  ElMessage.success('已通过')
  load()
}

async function reject(row: Template) {
  await rejectTemplate(row.id)
  ElMessage.success('已驳回')
  load()
}

async function remove(row: Template) {
  await ElMessageBox.confirm(`确认删除模板「${row.title}」？被活动引用的模板不可删除。`, '删除确认', { type: 'warning' })
  await deleteTemplate(row.id)
  ElMessage.success('已删除')
  load()
}

const filtered = () =>
  templates.value.filter((t) => !filter.value || t.title.includes(filter.value) || String(t.channel).includes(filter.value))

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>消息模板（{{ templates.length }}）· 审核流 DRAFT→PENDING→APPROVED</span>
        <div style="display: flex; gap: 10px">
          <el-input v-model="filter" placeholder="按标题/通道筛选" clearable style="width: 180px" />
          <el-button type="primary" size="small" @click="openCreate">新建模板</el-button>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="filtered()" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="140" />
      <el-table-column label="通道" width="90">
        <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
      </el-table-column>
      <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
      <el-table-column label="变量" width="160">
        <template #default="{ row }">{{ (row.vars ?? []).join(', ') || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.reviewStatus)" size="small">{{ row.reviewStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <template v-if="['DRAFT', 'REJECTED'].includes(row.reviewStatus)">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="primary" @click="submit(row)">提交审核</el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
          </template>
          <template v-else-if="row.reviewStatus === 'PENDING' && auth.role === 'REVIEWER'">
            <el-button size="small" type="success" @click="approve(row)">通过</el-button>
            <el-button size="small" type="danger" @click="reject(row)">驳回</el-button>
          </template>
          <span v-else style="color: #c0c4cc; font-size: 12px">{{ row.reviewStatus === 'PENDING' ? '待 REVIEWER 审核' : '已定稿' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑模板' : '新建模板'" width="620px" destroy-on-close>
    <el-form label-width="80px">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" placeholder="模板标题" />
      </el-form-item>
      <el-form-item label="通道" required>
        <el-select v-model="form.channel" style="width: 100%">
          <el-option v-for="c in Object.keys(CHANNEL_LABELS)" :key="c" :value="c" :label="`${CHANNEL_LABELS[c]}（${c}）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="内容" required>
        <el-input v-model="form.content" type="textarea" :rows="6" :placeholder="'消息内容，支持 {{name}} {{order_id}} 等变量占位符'" />
      </el-form-item>
      <el-form-item label="变量">
        <span v-pre style="color: #909399; font-size: 12px">保存后自动提取 content 中的 {{var}} 占位符（取值：事件 → 客户属性 → 姓名/手机/邮箱）</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
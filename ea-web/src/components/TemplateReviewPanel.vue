<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createTemplateReview, getTemplate, listTemplateReviews, listTemplates } from '../api/templates'
import type { Template, TemplateReview } from '../api/types'
import { useAuthStore } from '../stores/auth'

const props = defineProps<{ chatId?: number; templateId?: number; disabled?: boolean }>()
const emit = defineEmits<{ reviewed: [review: TemplateReview] }>()
const auth = useAuthStore()
const templates = ref<Template[]>([])
const selectedId = ref<number>()
const current = ref<Template | null>(null)
const reviews = ref<TemplateReview[]>([])
const comment = ref('')
const loading = ref(false)
const saving = ref(false)
const needsRefresh = ref(false)
const historyOpen = ref(false)
let generation = 0
let disposed = false

const needsChat = computed(() => props.chatId == null && props.templateId == null)
const fixedTemplate = computed(() => props.chatId == null && props.templateId != null)
const isReviewer = computed(() => auth.role === 'REVIEWER')
const canSubmit = computed(() => auth.loggedIn && !needsChat.value && !!current.value
  && !props.disabled && !loading.value && !saving.value && !needsRefresh.value && comment.value.length <= 2000)
const canDecide = computed(() => canSubmit.value && isReviewer.value && current.value?.reviewStatus === 'PENDING')
const statusLabels: Record<string, string> = {
  DRAFT: '草稿', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回',
}
const decisionLabels: Record<TemplateReview['decision'], string> = {
  COMMENT: '批注（非审批）', APPROVE: '人工通过', REJECT: '人工驳回',
}

function active(request: number) {
  return !disposed && request === generation
}

function clearDetail() {
  current.value = null
  reviews.value = []
  needsRefresh.value = false
  historyOpen.value = false
}

async function loadDetail(id: number, request: number) {
  try {
    const [template, history] = await Promise.all([getTemplate(id), listTemplateReviews(id)])
    if (!active(request)) return
    current.value = template
    reviews.value = history
    templates.value = templates.value.map((item) => item.id === id ? template : item)
    if (!templates.value.some((item) => item.id === id)) templates.value.push(template)
    needsRefresh.value = false
  } catch {
    // HTTP 拦截器统一提示错误；失败后必须重新读取，不能对未知版本提交。
    if (active(request)) needsRefresh.value = true
  }
}

async function initialize(request: number) {
  // auth.login 同步更新 store 与 localStorage；等待本轮更新完成后才发请求。
  await Promise.resolve()
  if (!active(request) || !auth.loggedIn || needsChat.value) return
  loading.value = true
  try {
    const items = await listTemplates()
    if (!active(request)) return
    templates.value = items
    selectedId.value = props.templateId ?? items.find((item) => item.reviewStatus === 'PENDING')?.id ?? items[0]?.id
    if (selectedId.value != null) await loadDetail(selectedId.value, request)
  } catch {
    if (active(request)) needsRefresh.value = true
  } finally {
    if (active(request)) loading.value = false
  }
}

watch(() => [props.chatId, props.templateId, auth.token, auth.tenantId, auth.role], () => {
  const request = ++generation
  templates.value = []
  selectedId.value = props.templateId
  comment.value = ''
  loading.value = false
  saving.value = false
  clearDetail()
  void initialize(request)
}, { immediate: true, flush: 'sync' })

async function selectTemplate(id: number) {
  if (saving.value || fixedTemplate.value) return
  const request = ++generation
  selectedId.value = id
  comment.value = ''
  clearDetail()
  loading.value = true
  try {
    await loadDetail(id, request)
  } finally {
    if (active(request)) loading.value = false
  }
}

async function refresh() {
  if (saving.value || loading.value) return
  const request = ++generation
  clearDetail()
  if (selectedId.value == null) {
    await initialize(request)
    return
  }
  loading.value = true
  try {
    await loadDetail(selectedId.value, request)
  } finally {
    if (active(request)) loading.value = false
  }
}

async function submit(decision: TemplateReview['decision']) {
  const template = current.value
  if (!canSubmit.value || !template) return
  if (decision !== 'COMMENT' && !canDecide.value) return
  const text = comment.value.trim()
  if (decision !== 'APPROVE' && !text) {
    ElMessage.warning(decision === 'REJECT' ? '请填写驳回原因' : '请填写批注')
    return
  }
  const request = generation
  saving.value = true
  try {
    const review = await createTemplateReview(template.id, {
      version: template.version,
      decision,
      comment: text,
      ...(props.chatId != null ? { chatId: props.chatId } : {}),
    })
    if (!active(request)) return
    comment.value = ''
    ElMessage.success(decision === 'COMMENT' ? '批注已保存，模板审核状态不变' : decision === 'APPROVE' ? '已人工通过' : '已人工驳回')
    emit('reviewed', review)
    if (!active(request)) return
    loading.value = true
    await loadDetail(template.id, request)
  } catch {
    // 保留输入；包括版本冲突在内的错误由 HTTP 层提示，刷新后重新核对原文。
    if (active(request)) needsRefresh.value = true
  } finally {
    if (active(request)) {
      saving.value = false
      loading.value = false
    }
  }
}

onBeforeUnmount(() => {
  disposed = true
  generation++
  comment.value = ''
  clearDetail()
})
</script>

<template>
  <section class="template-review-panel" aria-label="HITL 模板人工审核">
    <div class="panel-heading">
      <strong>HITL · 模板人工审核</strong>
      <el-button v-if="auth.loggedIn && !needsChat" size="small" :disabled="loading || saving" @click="refresh">刷新原文与记录</el-button>
    </div>
    <p class="panel-hint">批注不等于通过。待审核模板不能发送；最终通过或驳回由 REVIEWER 点击按钮决定，不由模型决定。</p>
    <el-alert v-if="!auth.loggedIn" title="请先登录后查看模板审核。" type="info" :closable="false" />
    <el-alert v-else-if="needsChat" title="请先创建或选择聊天，再进行模板批注与人工审核。" type="info" :closable="false" />
    <div v-else v-loading="loading" class="panel-body">
      <label class="selector-label">
        <span>审核模板</span>
        <el-select :model-value="selectedId" placeholder="选择模板" filterable :disabled="saving || fixedTemplate" @change="selectTemplate">
          <el-option v-for="item in templates" :key="item.id" :label="`${item.title} (#${item.id})`" :value="item.id" />
        </el-select>
      </label>
      <el-alert v-if="needsRefresh" title="读取或提交未完成。输入已保留，请刷新原文与记录，核对最新版本后再操作。" type="warning" :closable="false" />
      <p v-if="!loading && !needsRefresh && !templates.length" class="panel-hint">暂无模板，请先在模板管理中创建。</p>
      <template v-if="current">
        <div class="template-meta">
          <strong>{{ current.title }}</strong>
          <span>#{{ current.id }} · 版本 {{ current.version }}</span>
          <el-tag size="small" :type="current.reviewStatus === 'PENDING' ? 'warning' : current.reviewStatus === 'APPROVED' ? 'success' : current.reviewStatus === 'REJECTED' ? 'danger' : 'info'">
            {{ statusLabels[current.reviewStatus ?? ''] ?? current.reviewStatus ?? '未知状态' }}
          </el-tag>
        </div>
        <pre class="template-content">{{ current.content }}</pre>
        <label class="comment-label">
          <span>批注 / 审核意见</span>
          <el-input v-model="comment" type="textarea" :rows="3" :maxlength="2000" show-word-limit :disabled="props.disabled || saving" placeholder="批注与驳回必须填写；通过时意见可选。最多 2000 字。" />
        </label>
        <div class="review-actions">
          <el-button :disabled="!canSubmit || !comment.trim()" :loading="saving" @click="submit('COMMENT')">保存批注（不通过）</el-button>
          <template v-if="isReviewer">
            <el-button type="success" :disabled="!canDecide" @click="submit('APPROVE')">人工通过</el-button>
            <el-button type="danger" :disabled="!canDecide || !comment.trim()" @click="submit('REJECT')">人工驳回</el-button>
          </template>
        </div>
        <p v-if="!isReviewer" class="panel-hint">当前角色可保存批注；仅 REVIEWER 可以最终通过或驳回。</p>
        <p v-else-if="current.reviewStatus !== 'PENDING'" class="panel-hint">仅待审核（PENDING）模板可最终通过或驳回。请在模板管理页编辑或提交审核。</p>
        <p v-if="props.disabled" class="panel-hint">当前暂不可提交批注或审核决定。</p>
        <el-button class="history-toggle" text :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">
          {{ historyOpen ? '收起' : '展开' }}审核历史（{{ reviews.length }}）
        </el-button>
        <div v-if="historyOpen" class="review-history">
          <p v-if="!reviews.length" class="panel-hint">暂无批注或审核记录。</p>
          <article v-for="review in reviews" :key="review.id" class="history-item">
            <div class="template-meta">
              <strong>{{ decisionLabels[review.decision] }}</strong>
              <span>版本 {{ review.templateVersion }} · 用户 #{{ review.userId }}</span>
              <time>{{ review.createdAt }}</time>
            </div>
            <p class="review-comment">{{ review.comment || '（未填写意见）' }}</p>
            <details>
              <summary>查看当时原文</summary>
              <strong class="snapshot-title">{{ review.title }}</strong>
              <pre class="template-content">{{ review.content }}</pre>
            </details>
          </article>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.template-review-panel {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}
.panel-heading, .template-meta, .review-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.panel-heading { justify-content: space-between; }
.panel-hint { margin: 8px 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.panel-body { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.selector-label { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px; }
.selector-label .el-select { flex: 1; min-width: 160px; }
.template-meta { overflow-wrap: anywhere; font-size: 12px; color: var(--el-text-color-secondary); }
.template-meta strong { color: var(--el-text-color-primary); }
.template-content {
  margin: 0;
  padding: 10px;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  font-size: 13px;
  line-height: 1.6;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.comment-label { font-size: 13px; }
.review-actions .el-button + .el-button { margin-left: 0; }
.history-toggle { align-self: flex-start; }
.review-history { display: flex; flex-direction: column; gap: 12px; }
.history-item { border-top: 1px solid var(--el-border-color-lighter); padding-top: 10px; }
.review-comment { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.history-item summary { cursor: pointer; font-size: 12px; color: var(--el-text-color-secondary); }
.snapshot-title { display: block; margin: 8px 0; overflow-wrap: anywhere; font-size: 13px; }
@media (max-width: 480px) {
  .template-review-panel { padding: 10px; }
  .selector-label .el-select { flex-basis: 100%; min-width: 0; }
}
</style>

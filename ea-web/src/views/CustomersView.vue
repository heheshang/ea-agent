<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '../api/http'
import type { PageResult, Row } from '../api/types'

const rows = ref<Row[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const page = await get<PageResult<Row>>('/objects/customer', { limit: 50 })
    rows.value = page.items
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>客户对象（/api/objects/customer，租户隔离）</span>
        <el-button type="primary" size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column prop="email" label="邮箱" width="200" />
      <el-table-column prop="attributes" label="属性" min-width="200">
        <template #default="{ row }">{{ JSON.stringify(row.attributes ?? {}) }}</template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="180" />
    </el-table>
    <div class="total">共 {{ total }} 条（cursor 分页，limit=50）</div>
  </el-card>
</template>

<style scoped>
.total {
  margin-top: 10px;
  color: #909399;
  font-size: 13px;
}
</style>
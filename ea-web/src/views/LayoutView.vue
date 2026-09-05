<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

function logout() {
  localStorage.removeItem('ea.session_id')
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container style="height: 100%">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <span class="brand-logo">EA</span>
        <span class="brand-name">EA-Agent</span>
      </div>
      <el-menu router :default-active="$route.path" class="side-menu">
        <el-menu-item index="/workbench">💬 Agent 工作台</el-menu-item>
        <el-menu-item index="/customers">👥 客户管理</el-menu-item>
        <el-menu-item index="/campaigns">🚀 运营活动</el-menu-item>
        <el-menu-item index="/stats">📊 统计看板</el-menu-item>
        <el-menu-item index="/retention">📈 存留看板</el-menu-item>
        <el-menu-item index="/knowledge">📚 知识库</el-menu-item>
        <el-menu-item index="/ontology">🧭 Ontology 链路</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="tenant-chip">租户 #{{ auth.tenantId }} · {{ auth.name }}（{{ auth.role }}）</span>
        <el-button link type="primary" class="logout-btn" @click="logout">退出</el-button>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <keep-alive include="AgentWorkbenchView">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.aside {
  background: #fff;
  border-right: 1px solid #f0f1f3;
  display: flex;
  flex-direction: column;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 18px 16px;
}
.brand-logo {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, #3370ff, #5c8bff);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 3px 10px rgba(51, 112, 255, 0.35);
}
.brand-name {
  font-weight: 700;
  font-size: 17px;
  letter-spacing: 0.5px;
  color: #1d2129;
}
.side-menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #4e5969;
  --el-menu-active-color: #3370ff;
  --el-menu-hover-bg-color: #f4f8ff;
  --el-menu-item-height: 44px;
  border-right: none;
  flex: 1;
  padding: 4px 10px;
}
.side-menu .el-menu-item {
  border-radius: 10px;
  margin-bottom: 2px;
  font-size: 14px;
}
.side-menu .el-menu-item.is-active {
  background: #e9f0ff;
  font-weight: 600;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #f0f1f3;
  height: 56px;
}
.tenant-chip {
  background: #f4f8ff;
  color: #3370ff;
  font-size: 13px;
  font-weight: 500;
  padding: 5px 14px;
  border-radius: 999px;
}
.logout-btn {
  font-size: 13px;
  color: #4e5969;
}
.logout-btn:hover {
  color: #3370ff;
}
.main {
  padding: 20px;
  overflow-y: auto;
  background: var(--db-bg);
}
</style>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, User, Promotion, Document, DataAnalysis, TrendCharts, Collection, Connection, Fold, Close, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const menuOpen = ref(false)
const navigation = [
  { label: '智能协作', items: [
    { path: '/workbench', label: 'Agent 工作台', icon: ChatDotRound },
    { path: '/knowledge', label: '知识库', icon: Collection },
    { path: '/ontology', label: 'Ontology 链路', icon: Connection },
  ] },
  { label: '运营管理', items: [
    { path: '/customers', label: '客户管理', icon: User },
    { path: '/campaigns', label: '运营活动', icon: Promotion },
    { path: '/templates', label: '消息模板', icon: Document },
  ] },
  { label: '数据洞察', items: [
    { path: '/stats', label: '统计看板', icon: DataAnalysis },
    { path: '/retention', label: '存留看板', icon: TrendCharts },
  ] },
]
const currentPage = computed(() => navigation.flatMap(group => group.items).find(item => item.path === route.path)?.label ?? '运营控制台')
watch(() => route.path, () => { menuOpen.value = false })
function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <button v-if="menuOpen" class="nav-scrim" aria-label="关闭导航" @click="menuOpen = false" />
    <aside id="app-navigation" class="sidebar" :class="{ 'is-open': menuOpen }" aria-label="主导航" @keydown.esc="menuOpen = false">
      <router-link to="/workbench" class="brand">
        <span class="brand-logo">EA<span /></span>
        <span><strong>EA-Agent</strong><small>智能运营工作空间</small></span>
      </router-link>
      <button class="mobile-close" aria-label="关闭导航" @click="menuOpen = false"><el-icon><Close /></el-icon></button>
      <nav class="navigation">
        <div v-for="group in navigation" :key="group.label" class="nav-group">
          <div class="nav-caption">{{ group.label }}</div>
          <router-link v-for="item in group.items" :key="item.path" :to="item.path" class="nav-link" :aria-current="route.path === item.path ? 'page' : undefined">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <span v-if="route.path === item.path" class="active-dot" />
          </router-link>
        </div>
      </nav>
      <div class="sidebar-footer"><span class="workspace-symbol">W</span><div><strong>企业工作空间</strong><small>租户 #{{ auth.tenantId }}</small></div><el-icon><Connection /></el-icon></div>
    </aside>
    <div class="app-content">
      <header class="app-header">
        <div class="breadcrumb"><button class="mobile-toggle" aria-label="打开导航" aria-controls="app-navigation" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><el-icon><Fold /></el-icon></button><span class="breadcrumb-root">工作空间</span><span class="breadcrumb-divider">/</span><strong>{{ currentPage }}</strong></div>
        <div class="account"><span class="account-avatar">{{ auth.name?.slice(0, 1) || 'U' }}</span><div class="account-info"><strong>{{ auth.name }}</strong><small>{{ auth.role }}</small></div><span class="account-divider" /><el-button text class="logout-btn" :icon="SwitchButton" @click="logout">退出</el-button></div>
      </header>
      <main class="main">
        <router-view v-slot="{ Component }"><keep-alive include="AgentWorkbenchView"><component :is="Component" /></keep-alive></router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell { display: flex; height: 100dvh; overflow: hidden; }
.sidebar { width: 232px; flex: 0 0 232px; display: flex; flex-direction: column; background: #14243b; color: #fff; position: relative; }
.brand { display: flex; align-items: center; gap: 12px; padding: 30px 24px 32px; text-decoration: none; color: #fff; }
.brand-logo { display: grid; place-items: center; position: relative; width: 40px; height: 40px; border-radius: 12px; background: #3864ed; font-size: 16px; font-weight: 800; letter-spacing: -1px; }
.brand-logo span { position: absolute; width: 7px; height: 7px; border-radius: 50%; background: #a8cdff; right: -2px; top: -2px; border: 3px solid #14243b; }
.brand strong { font-size: 18px; letter-spacing: -.3px; }
.brand small { display: block; margin-top: 5px; font-size: 10px; letter-spacing: 1.4px; color: #a4b3c8; }
.navigation { flex: 1; overflow-y: auto; padding: 0 14px; }
.nav-group { margin-bottom: 26px; }
.nav-caption { padding: 0 14px 12px; color: #91a3bd; font-size: 10px; letter-spacing: 2px; }
.nav-link { display: flex; align-items: center; gap: 12px; min-height: 46px; padding: 0 14px; margin: 4px 0; border-radius: 9px; color: #bdc9da; text-decoration: none; font-size: 13px; transition: background .18s, color .18s; }
.nav-link .el-icon { font-size: 18px; }
.nav-link:hover { background: #20334e; color: #fff; }
.nav-link.router-link-active { background: #3864ed; color: #fff; box-shadow: 0 5px 18px #07193530; }
.active-dot { margin-left: auto; width: 5px; height: 5px; border-radius: 50%; background: #c5d3ff; }
.sidebar-footer { margin: 0 20px; padding: 20px 0 24px; border-top: 1px solid #304058; display: flex; align-items: center; gap: 10px; }
.workspace-symbol { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 8px; background: #283b54; color: #c7d8f0; font-size: 12px; }
.sidebar-footer strong { font-size: 11px; font-weight: 500; }
.sidebar-footer small { display: block; color: #9cacc2; font-size: 10px; margin-top: 4px; }
.sidebar-footer > .el-icon { margin-left: auto; color: #9cacc2; }
.app-content { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.app-header { flex-shrink: 0; height: 76px; padding: 0 30px; border-bottom: 1px solid var(--ea-border); background: #fff; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.breadcrumb { display: flex; gap: 14px; align-items: center; min-width: 0; font-size: 12px; white-space: nowrap; }
.breadcrumb-root { color: var(--ea-text-muted); }
.breadcrumb-divider { color: #bcc6d4; }
.breadcrumb strong { font-weight: 600; }
.account { display: flex; align-items: center; gap: 10px; }
.account-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; background: #edf2ff; color: #3864ed; font-size: 13px; font-weight: 600; }
.account-info strong { display: block; font-size: 12px; font-weight: 600; }
.account-info small { display: block; margin-top: 3px; font-size: 9px; letter-spacing: .6px; color: var(--ea-text-muted); }
.account-divider { height: 24px; width: 1px; background: var(--ea-border); margin: 0 4px 0 10px; }
.logout-btn { color: var(--ea-text-muted); font-size: 12px; }
.main { padding: 28px; flex: 1; min-height: 0; overflow: auto; background: var(--db-bg); }
.main > :deep(*) { max-width: 1600px; margin-left: auto; margin-right: auto; }
.mobile-toggle, .mobile-close, .nav-scrim { display: none; }
@media (max-width: 1100px) and (min-width: 769px) {
  .sidebar { width: 208px; flex-basis: 208px; }
  .brand { padding-left: 18px; padding-right: 18px; }
  .app-header { padding: 0 24px; }
}
@media (max-width: 768px) {
  .sidebar { position: fixed; inset: 0 auto 0 0; z-index: 2002; transform: translateX(-100%); transition: transform .2s; width: 256px; visibility: hidden; }
  .sidebar.is-open { transform: translateX(0); visibility: visible; }
  .nav-scrim { display: block; position: fixed; inset: 0; border: 0; background: #0d1d3566; z-index: 2001; }
  .mobile-toggle, .mobile-close { display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 6px; background: transparent; color: inherit; cursor: pointer; font-size: 20px; }
  .mobile-close { position: absolute; right: 6px; top: 7px; color: #bdc9da; }
  .app-header { height: 64px; padding: 0 16px; gap: 8px; }
  .breadcrumb { gap: 8px; }
  .breadcrumb-root, .breadcrumb-divider, .account-info, .account-divider { display: none; }
  .account { gap: 4px; }
  .account-avatar { width: 28px; height: 28px; }
  .logout-btn { padding: 8px; }
  .main { padding: 16px; }
}
@media (prefers-reduced-motion: reduce) { .sidebar, .nav-link { transition: none; } }
</style>

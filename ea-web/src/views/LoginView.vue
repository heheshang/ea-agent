<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ loginName: 'admin', password: 'admin123' })

async function submit() {
  loading.value = true
  try {
    await auth.login(form.loginName, form.password)
    ElMessage.success(`欢迎，${auth.name}`)
    router.push(String(route.query.redirect ?? '/workbench'))
  } catch {
    // http 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="deco deco-1"></div>
    <div class="deco deco-2"></div>
    <el-card class="login-card">
      <div class="brand">
        <span class="brand-logo">EA</span>
      </div>
      <h2 class="title">EA-Agent 运营控制台</h2>
      <p class="sub">租户隔离 · 人群触达 · Agent 助手</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="登录名">
          <el-input v-model="form.loginName" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="admin123" />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="login-btn" @click="submit">登 录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f6f7f9;
  position: relative;
  overflow: hidden;
}
.deco {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.5;
  pointer-events: none;
}
.deco-1 {
  width: 420px;
  height: 420px;
  background: #d5e2ff;
  top: -120px;
  left: -80px;
}
.deco-2 {
  width: 380px;
  height: 380px;
  background: #e9f0ff;
  bottom: -140px;
  right: -60px;
}
.login-card {
  width: 400px;
  padding: 12px 20px 24px;
  border-radius: 24px !important;
  position: relative;
  z-index: 1;
  text-align: center;
}
.brand {
  display: flex;
  justify-content: center;
  margin-bottom: 8px;
}
.brand-logo {
  width: 52px;
  height: 52px;
  border-radius: 16px;
  background: linear-gradient(135deg, #3370ff, #5c8bff);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(51, 112, 255, 0.35);
}
.title {
  margin: 6px 0 0;
  font-size: 22px;
  color: #1d2129;
}
.sub {
  color: #86909c;
  font-size: 13px;
  margin: 8px 0 24px;
}
.login-card :deep(.el-form-item__label) {
  font-weight: 500;
  color: #4e5969;
}
.login-btn {
  width: 100%;
  font-weight: 600;
  letter-spacing: 4px;
  background: linear-gradient(135deg, #3370ff, #5c8bff);
  border: none;
  color: #fff;
  box-shadow: 0 4px 14px rgba(51, 112, 255, 0.3);
}
.login-btn:hover {
  color: #fff;
  opacity: 0.92;
}
</style>
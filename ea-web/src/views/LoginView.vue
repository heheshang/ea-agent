<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ tenantDomain: 'demo.local', loginName: 'admin', password: 'admin123' })

async function submit() {
  if (loading.value) return
  loading.value = true
  try {
    await auth.login(form.tenantDomain, form.loginName, form.password)
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
  <main class="login-wrap">
    <section class="login-shell" aria-label="EA-Agent 登录">
      <div class="welcome-panel">
        <div class="brand">
          <span class="brand-logo" aria-hidden="true">EA</span>
          <div>
            <span class="brand-name">EA-Agent</span>
            <span class="brand-caption">智能运营控制台</span>
          </div>
        </div>

        <div class="welcome-content">
          <p class="eyebrow">连接数据与行动</p>
          <h1>让每一次运营，<br />都有清晰的方向。</h1>
          <p class="welcome-description">从业务对象到人群触达，在一个工作空间内完成运营决策与执行。</p>

          <ol class="workflow" aria-label="运营流程">
            <li>
              <span class="step-number">01</span>
              <strong>对象</strong>
              <span class="step-description">理解业务数据</span>
            </li>
            <li>
              <span class="step-number">02</span>
              <strong>决策</strong>
              <span class="step-description">洞察目标人群</span>
            </li>
            <li>
              <span class="step-number">03</span>
              <strong>触达</strong>
              <span class="step-description">推动行动落地</span>
            </li>
          </ol>
        </div>

        <p class="welcome-footer">租户隔离 <span aria-hidden="true">/</span> 人群触达 <span aria-hidden="true">/</span> Agent 助手</p>
      </div>

      <div class="form-panel">
        <div class="form-content">
          <p class="form-eyebrow">运营工作空间</p>
          <h2>欢迎回来</h2>
          <p class="form-description">登录你的账号，继续今天的工作。</p>
          <el-form label-position="top" :aria-busy="loading" @submit.prevent="submit">
            <el-form-item label="租户域名" for="tenant-domain">
              <el-input id="tenant-domain" v-model="form.tenantDomain" name="tenantDomain" autocomplete="organization" placeholder="请输入租户域名" />
            </el-form-item>
            <el-form-item label="登录名" for="login-name">
              <el-input id="login-name" v-model="form.loginName" name="username" autocomplete="username" placeholder="请输入登录名" />
            </el-form-item>
            <el-form-item label="密码" for="login-password">
              <el-input id="login-password" v-model="form.password" name="password" autocomplete="current-password" type="password" show-password placeholder="请输入密码" />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="loading" :disabled="loading" class="login-btn">{{ loading ? '正在登录…' : '登录控制台' }}</el-button>
          </el-form>
          <p class="form-note">使用所属租户的账号登录工作空间</p>
        </div>
        <p class="form-footer">EA-Agent <span aria-hidden="true">·</span> 运营控制台</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  box-sizing: border-box;
  background: var(--db-bg, #f3f6fa);
  color: #172b45;
}
.login-shell {
  display: grid;
  grid-template-columns: 1.08fr 1fr;
  width: 1120px;
  max-width: 100%;
  min-height: 660px;
  overflow: hidden;
  border: 1px solid var(--ea-border, #e3e9f1);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 48px rgba(20, 36, 59, 0.06);
}
.welcome-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 42px;
  background: #14243b;
  color: #fff;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 1px solid #526ea7;
  border-radius: 12px;
  background: #294775;
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.brand-name,
.brand-caption {
  display: block;
}
.brand-name {
  font-size: 18px;
  font-weight: 650;
  letter-spacing: 0.3px;
}
.brand-caption {
  margin-top: 4px;
  color: #a7b8d0;
  font-size: 11px;
  letter-spacing: 1px;
}
.welcome-content {
  margin: auto 0;
  padding: 56px 0;
}
.eyebrow {
  margin: 0 0 18px;
  color: #a9c0f5;
  font-size: 12px;
  letter-spacing: 2px;
}
.welcome-content h1 {
  margin: 0;
  font-size: clamp(28px, 2.5vw, 36px);
  line-height: 1.55;
  font-weight: 600;
  letter-spacing: -0.6px;
}
.welcome-description {
  max-width: 360px;
  margin: 20px 0 0;
  color: #b0bed1;
  font-size: 14px;
  line-height: 1.9;
}
.workflow {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 36px 0 0;
  padding: 0;
  list-style: none;
}
.workflow li {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid #425570;
}
.workflow li::before {
  position: absolute;
  top: -3px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #9ab7ff;
  content: '';
}
.step-number {
  color: #8ea8d5;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 1px;
}
.workflow strong {
  font-size: 15px;
  font-weight: 500;
}
.step-description {
  color: #a7b8d0;
  font-size: 11px;
}
.welcome-footer {
  margin: 0;
  color: #a7b8d0;
  font-size: 11px;
}
.welcome-footer span {
  margin: 0 12px;
  color: #60738e;
}
.form-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: 48px 60px 30px;
}
.form-content {
  width: 100%;
  max-width: 360px;
  margin: auto;
  padding: 32px 0;
}
.form-eyebrow {
  margin: 0 0 12px;
  color: var(--db-primary, #3864ed);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 1px;
}
.form-content h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 650;
  letter-spacing: -0.5px;
}
.form-description {
  margin: 12px 0 32px;
  color: var(--ea-text-muted, #69798f);
  font-size: 13px;
  line-height: 1.7;
}
.form-content :deep(.el-form-item) {
  margin-bottom: 22px;
}
.form-content :deep(.el-form-item__label) {
  margin-bottom: 8px;
  color: #344760;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
}
.form-content :deep(.el-input__wrapper) {
  min-height: 44px;
  padding: 0 14px;
  border-radius: 8px;
  background: #fcfdff;
}
.login-btn {
  width: 100%;
  height: 46px;
  margin-top: 4px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
}
.form-note {
  margin: 18px 0 0;
  color: var(--ea-text-muted, #69798f);
  text-align: center;
  font-size: 11px;
  line-height: 1.7;
}
.form-footer {
  margin: 0;
  color: var(--ea-text-muted, #69798f);
  font-size: 11px;
  text-align: center;
}
.form-footer span {
  margin: 0 7px;
}
@media (max-width: 1000px) {
  .login-wrap {
    padding: 28px;
  }
  .welcome-panel {
    padding: 32px;
  }
  .form-panel {
    padding: 32px;
  }
}
@media (max-width: 768px) {
  .login-wrap {
    align-items: flex-start;
    padding: 16px;
  }
  .login-shell {
    grid-template-columns: minmax(0, 1fr);
    max-width: 480px;
    min-height: 0;
  }
  .welcome-panel {
    padding: 24px;
  }
  .brand-logo {
    width: 36px;
    height: 36px;
    border-radius: 10px;
    font-size: 15px;
  }
  .brand-name {
    font-size: 16px;
  }
  .brand-caption {
    font-size: 10px;
  }
  .welcome-content {
    padding: 24px 0 0;
  }
  .welcome-content h1 {
    font-size: 25px;
    line-height: 1.5;
  }
  .eyebrow,
  .welcome-description,
  .step-description,
  .welcome-footer {
    display: none;
  }
  .workflow {
    gap: 16px;
    margin-top: 22px;
  }
  .workflow li {
    flex-direction: row;
    align-items: center;
    gap: 8px;
    padding-top: 12px;
  }
  .workflow strong {
    font-size: 12px;
  }
  .step-number {
    font-size: 10px;
  }
  .form-panel {
    padding: 28px 24px 22px;
  }
  .form-content {
    padding: 0;
  }
  .form-eyebrow {
    display: none;
  }
  .form-content h2 {
    font-size: 24px;
  }
  .form-description {
    margin: 8px 0 24px;
  }
  .form-content :deep(.el-form-item) {
    margin-bottom: 18px;
  }
  .form-footer {
    margin-top: 24px;
  }
}
</style>
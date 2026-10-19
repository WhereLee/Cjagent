<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'

const LAST_LOGIN_KEY = 'cabinet-admin-last-login'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function loadLastLogin(): { tenantCode: string; username: string } {
  try {
    const raw = localStorage.getItem(LAST_LOGIN_KEY)
    if (!raw) return { tenantCode: '', username: '' }
    const value = JSON.parse(raw) as { tenantCode?: string; username?: string }
    return { tenantCode: value.tenantCode ?? '', username: value.username ?? '' }
  } catch {
    return { tenantCode: '', username: '' }
  }
}

const last = loadLastLogin()
const formRef = ref<FormInstance>()
const form = reactive({ tenantCode: last.tenantCode, username: last.username, password: '' })
const loading = ref(false)

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await auth.login(form.tenantCode.trim(), form.username.trim(), form.password)
    localStorage.setItem(
      LAST_LOGIN_KEY,
      JSON.stringify({ tenantCode: form.tenantCode.trim(), username: form.username.trim() }),
    )
    const redirect =
      typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
        ? route.query.redirect
        : '/'
    await router.push(redirect)
  } catch (e) {
    // message 是后端给的中文文案，直接展示，不自己拼
    ElMessage.error(e instanceof ApiError ? e.message : '登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="brand-panel">
      <div class="logo">柜</div>
      <h1 class="brand-title">储物柜运营后台</h1>
      <p class="brand-sub">工单、异常台账、资金与定价，一处处置。</p>
      <ul class="brand-list">
        <li>工单按线上 / 线下两条队列分流，认领与处理都留痕。</li>
        <li>异常格口按滞留时长排序，清柜与强制开柜必须写事由。</li>
        <li>AI 简报与运维诊断只出草稿，执行动作永远由人确认。</li>
      </ul>
    </div>

    <el-card class="login-card">
      <h2 class="title">登录</h2>
      <p class="sub">账号由运维创建，仓库不内置任何可用口令。</p>
      <el-form ref="formRef" :model="form" label-position="top" @submit.prevent="onSubmit">
        <el-form-item
          label="租户编码"
          prop="tenantCode"
          :rules="[{ required: true, message: '请输入租户编码', trigger: 'blur' }]"
        >
          <el-input v-model="form.tenantCode" placeholder="例如 platform" autocomplete="off" />
        </el-form-item>
        <el-form-item
          label="用户名"
          prop="username"
          :rules="[{ required: true, message: '请输入用户名', trigger: 'blur' }]"
        >
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
        <el-form-item
          label="密码"
          prop="password"
          :rules="[{ required: true, message: '请输入密码', trigger: 'blur' }]"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="off"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit-btn">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: stretch;
  background: var(--el-fill-color-lighter);
}

.brand-panel {
  flex: 1.1;
  padding: 64px 56px;
  background: linear-gradient(150deg, #1f3c88, #2b6de5 60%, #5b8ff0);
  color: #ffffff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.logo {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.16);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-weight: 600;
  margin-bottom: 24px;
}

.brand-title {
  margin: 0;
  font-size: 30px;
  font-weight: 600;
}

.brand-sub {
  margin: 12px 0 28px;
  font-size: 15px;
  opacity: 0.9;
}

.brand-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  opacity: 0.88;
}

.login-card {
  width: 420px;
  margin: auto 8%;
  border: none;
  box-shadow: 0 12px 40px rgba(31, 39, 51, 0.08);
}

.title {
  margin: 4px 0 4px;
  font-size: 22px;
}

.sub {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
}

@media (max-width: 900px) {
  .brand-panel {
    display: none;
  }

  .login-card {
    margin: auto;
    width: 90%;
  }
}
</style>

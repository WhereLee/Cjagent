<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ tenantCode: '', username: '', password: '' })

const rules: FormRules<typeof form> = {
  tenantCode: [{ required: true, message: '请输入租户编码', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  // 与后端 @Size(min=6) 对齐；服务端仍然校验，这里只是省一次往返
  password: [
    { required: true, message: '请输入口令', trigger: 'blur' },
    { min: 6, max: 64, message: '口令长度应在 6-64 之间', trigger: 'blur' },
  ],
}

async function submit() {
  // 回车与点按钮会走同一个 submit；ElButton 的 loading 只禁用点击，不禁用 keyup.enter。
  // **标志位必须在任何 await 之前同步置位**：上一版先 `await validate()` 再置 true，
  // 同一个 tick 内的多次触发都读到 false 而全部放行（浏览器实测能打出 3 次 login）。
  if (loading.value) return
  loading.value = true

  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return

    await auth.login({ ...form })
    ElMessage.success('登录成功')
    // redirect 只接受站内绝对路径，避免 ?redirect=https://evil 变成开放重定向
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.replace(redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/dashboard')
  } catch (error) {
    // 失败文案由 http 拦截器统一弹出；这里只保留可定位的原始 code 到控制台
    if (error instanceof ApiError) {
      console.warn(`登录失败 code=${error.code} traceId=${error.traceId ?? '-'}`)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <ElCard class="login-card">
      <h2 class="title">换电柜 SaaS 运营后台</h2>
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
        <ElFormItem label="租户编码" prop="tenantCode">
          <ElInput v-model.trim="form.tenantCode" placeholder="platform" autocomplete="off" />
        </ElFormItem>
        <ElFormItem label="用户名" prop="username">
          <ElInput v-model.trim="form.username" autocomplete="off" />
        </ElFormItem>
        <ElFormItem label="口令" prop="password">
          <ElInput v-model="form.password" type="password" show-password autocomplete="off" />
        </ElFormItem>
        <ElButton type="primary" class="submit" :loading="loading" @click="submit">登录</ElButton>
      </ElForm>
    </ElCard>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.login-card {
  width: 360px;
}

.title {
  margin: 0 0 20px;
  font-size: 18px;
  text-align: center;
}

.submit {
  width: 100%;
}
</style>

<script setup lang="ts">
import { ref } from 'vue'

import { useRiderStore } from '@/stores/rider'

const store = useRiderStore()
const tenantCode = ref('platform')
const loading = ref(false)

async function submit() {
  // 标志位必须在任何 await 之前同步置位（admin 那边实测过：置晚了同一 tick 的连点会全部放行）
  if (loading.value) return
  loading.value = true

  try {
    const newRegister = await store.login(tenantCode.value.trim())
    uni.showToast({ title: newRegister ? '已为你开通账号' : '登录成功', icon: 'none' })
    uni.reLaunch({ url: '/pages/home/index' })
  } catch {
    // 失败提示由 http 层按错误码统一弹出，这里不再重复
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="card">
    <view class="title">骑手登录</view>
    <view class="row">
      <text class="label">运营租户编码</text>
      <input v-model="tenantCode" class="input" placeholder="platform" />
    </view>
   <!--
     不用 uni 的 <button type="primary" loading>：vue-tsc 把 button 当 HTML 内置元素，
     uni-app 到编译期才把它换成自己的组件，所以那些自定义 prop 在类型层面不存在。
     用 :disabled（HTML 合法属性）+ class 承担同样的行为与观感，不为了过类型检查而关 typecheck。
   -->
    <button class="btn btn-primary" :disabled="loading" @click="submit">
      {{ loading ? '登录中…' : '登录 / 注册' }}
    </button>
    <view class="hint">
      本地调试使用模拟登录通道（免微信授权）；正式发布后改走微信授权登录。
      若提示“租户不可用”，请确认输入的是运营商给你的编码，而不是账号或手机号。
    </view>
  </view>
</template>

<style scoped>
.input {
  flex: 1;
  text-align: right;
}

.btn-primary {
  background: #2b3a4a;
  color: #fff;
  border-radius: 12rpx;
}

.btn-primary[disabled] {
  background: #a0a7b0;
  color: #fff;
}
</style>

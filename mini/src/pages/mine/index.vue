<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useCustomerStore } from '@/stores/customer'
import { ensureAuthenticated } from '@/utils/authz'

const store = useCustomerStore()
const ready = ref(false)

onShow(async () => {
  ready.value = await ensureAuthenticated()
})

function goHome(): void {
  uni.reLaunch({ url: '/pages/home/index' })
}

/** 退出是丢登录态的动作，给一次确认；点错一下就要重新登录，在户外很痛。 */
function confirmLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '确定要退出当前账号吗？',
    confirmText: '退出',
    confirmColor: '#f56c6c',
    success: (res) => {
      if (res.confirm) void store.logout()
    },
  })
}
</script>

<template>
  <view v-if="ready" class="card">
    <view class="title">我的</view>
    <view class="row">
      <text class="label">客户 ID</text>
      <text class="value mono">{{ store.profile?.customerId }}</text>
    </view>
    <view class="row">
      <text class="label">所属运营方（报障编号）</text>
      <text class="value mono">{{ store.profile?.tenantId }}</text>
    </view>
    <view class="row">
      <text class="label">昵称</text>
      <text class="value">{{ store.profile?.nickname || '未设置' }}</text>
    </view>
    <view class="row">
      <text class="label">手机号</text>
      <text class="value mono">{{ store.profile?.phone || '未绑定' }}</text>
    </view>
    <view class="row">
      <text class="label">账号状态</text>
      <!-- 拿不到 profile 时显示“—”：把“未登录 / 没拉到数据”说成“账号已冻结”属于假数据，会误导骑手去找客服 -->
      <text class="value">{{ store.profile ? (store.profile.status === 1 ? '正常' : '已冻结') : '—' }}</text>
    </view>
    <view class="row">
      <text class="label">注册时间</text>
      <text class="value mono">{{ store.profile?.registerTime || '-' }}</text>
    </view>
    <view class="row">
      <text class="label">最后登录</text>
      <text class="value mono">{{ store.profile?.lastLoginAt || '-' }}</text>
    </view>

    <button class="btn btn-ghost" @click="goHome">返回首页</button>
    <button class="btn btn-danger" @click="confirmLogout">退出登录</button>

    <view class="hint">
      手机号为脱敏显示；如需修改或申诉，请携带上方报障编号联系运营商客服。
    </view>
  </view>

  <!-- 拿不到身份时显示兜底块，而不是把状态猜成“已冻结” -->
  <need-login v-else />
</template>

<style scoped>
.btn-danger {
  background: #fff;
  color: #f56c6c;
  border: 1rpx solid #f56c6c;
  border-radius: 12rpx;
}
</style>

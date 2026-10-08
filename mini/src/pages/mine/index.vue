<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useRiderStore } from '@/stores/rider'
import { ensureAuthenticated } from '@/utils/authz'

const store = useRiderStore()
const ready = ref(false)

onShow(async () => {
  ready.value = await ensureAuthenticated()
})

function goHome(): void {
  uni.reLaunch({ url: '/pages/home/index' })
}
</script>

<template>
  <view v-if="ready" class="card">
    <view class="title">我的</view>
    <view class="row">
      <text class="label">骑手 ID</text>
      <text class="value mono">{{ store.profile?.riderId }}</text>
    </view>
    <view class="row">
      <text class="label">所属租户</text>
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
      <text class="value">{{ store.profile?.status === 1 ? '正常' : '已冻结' }}</text>
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
    <button class="btn btn-danger" @click="store.logout()">退出登录</button>

    <view class="hint">
      这里<b>看不到 openId</b>：它是服务端侧身份标识，接口回的是 RiderView 而不是实体。
      手机号是后端 @JsonMask 处理过的，客户端从来拿不到全文。
    </view>
  </view>
</template>

<style scoped>
.btn-danger {
  background: #fff;
  color: #f56c6c;
  border: 1rpx solid #f56c6c;
  border-radius: 12rpx;
}
</style>

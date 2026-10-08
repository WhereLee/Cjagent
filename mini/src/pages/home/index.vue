<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useRiderStore } from '@/stores/rider'
import { ensureAuthenticated } from '@/utils/authz'

const store = useRiderStore()
const ready = ref(false)

// onShow 而不是 onLoad：从扫码页返回时也要重新校验登录态（凭证可能在此期间失效）
onShow(async () => {
  ready.value = await ensureAuthenticated()
})

// 跳转目标写成**字面量**而不是 go(url) 这种变量参数：
// 页面流转图生成器只能识别字面量，写成变量就会让静态检查失明（扫不出不可达页面）
function goScan(): void {
  uni.navigateTo({ url: '/pages/scan/index' })
}

function goMine(): void {
  uni.navigateTo({ url: '/pages/mine/index' })
}
</script>

<template>
  <view v-if="ready">
    <view class="card">
      <view class="title">{{ store.profile?.nickname || '骑手' }}</view>
      <view class="row">
        <text class="label">骑手 ID</text>
        <text class="value mono">{{ store.profile?.riderId }}</text>
      </view>
      <view class="row">
        <text class="label">所属租户</text>
        <text class="value mono">{{ store.profile?.tenantId }}</text>
      </view>
    </view>

    <view class="card">
      <button class="btn btn-primary" @click="goScan">扫码换电</button>
      <button class="btn btn-ghost" @click="goMine">我的</button>
      <view class="hint">换电业务尚未接入（阶段 1 第 8～12 刀），这里的扫码入口目前是占位页面。</view>
    </view>
  </view>
</template>

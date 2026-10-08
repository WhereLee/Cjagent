<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ensureAuthenticated } from '@/utils/authz'

const ready = ref(false)
const lastResult = ref('')

onShow(async () => {
  ready.value = await ensureAuthenticated()
})

/**
 * 扫码占位。
 *
 * <p>`uni.scanCode` 在 H5 平台不支持，所以这里的 fail 分支不是错误处理，
 * 而是<b>常态</b>：H5 调试时必然走它。真机/小程序下才会返回内容。
 * 现在就把两个分支都写出来，是为了让第 8 刀接业务时不必改这个页面的骨架。
 */
function scan(): void {
  uni.scanCode({
    success: (res) => {
      lastResult.value = res.result
      uni.showToast({ title: '已识别柜机码', icon: 'none' })
    },
    fail: (err) => {
      lastResult.value = ''
      uni.showToast({ title: `当前平台不支持扫码：${err.errMsg}`, icon: 'none', duration: 3000 })
    },
  })
}

function back(): void {
  uni.navigateBack()
}
</script>

<template>
  <view v-if="ready" class="card">
    <view class="title">扫码换电（占位）</view>
    <button class="btn btn-primary" @click="scan">扫一扫柜机上的二维码</button>
    <view v-if="lastResult" class="row">
      <text class="label">扫到内容</text>
      <text class="value mono">{{ lastResult }}</text>
    </view>
    <button class="btn btn-ghost" @click="back">返回</button>
    <view class="hint">
      业务尚未接入：真实流程是「柜机码 → 定位柜机与空闲仓位 → 抢电池（并发锁） → 下发开锁指令（MQTT）」。
      H5 里点扫码必然失败（平台不支持），这是预期分支，不是 bug。
    </view>
  </view>
</template>

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
      换电功能尚未开放，扫码不会发起业务。在微信小程序或真机上才能扫码；
      普通浏览器里点“扫一扫”一定会提示不支持，这不是手机坏了。
    </view>
  </view>

  <need-login v-else />
</template>

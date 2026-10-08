<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

onLaunch(() => {
  // 启动时不预拉 /me：小程序冷启动每多一次网络请求就多一次白屏，
  // 真正需要身份的页面在自己的 onLoad 里拉（见 utils/authz.ts 的 ensureProfile）
  console.warn('cabinet-mini 启动完成')
})
</script>

<template>
  <slot />
</template>

<style>
page {
  background: #f5f7fa;
  color: #303133;
  font-size: 28rpx;
}

.card {
  margin: 24rpx;
  padding: 32rpx;
  background: #fff;
  border-radius: 16rpx;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #ebeef5;
}

.row:last-child {
  border-bottom: none;
}

.label {
  color: #909399;
}

.value {
  font-weight: 600;
}

.mono {
  font-family: Menlo, Consolas, monospace;
  font-size: 26rpx;
}

.title {
  margin: 0 0 24rpx;
  font-size: 34rpx;
  font-weight: 600;
}

.hint {
  margin-top: 24rpx;
  color: #909399;
  font-size: 22rpx;
  line-height: 1.6;
}

.btn {
  margin-top: 24rpx;
}

/* 两个全局按钮类：uni 的 <button type="primary"> 在 vue-tsc 里不是合法 prop，
   所以颜色走 class，行为走 :disabled */
.btn-primary {
  background: #2b3a4a;
  color: #fff;
  border-radius: 12rpx;
}

.btn-ghost {
  background: #fff;
  color: #2b3a4a;
  border: 1rpx solid #dcdfe6;
  border-radius: 12rpx;
}
</style>

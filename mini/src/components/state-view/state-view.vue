<script setup lang="ts">
/**
 * 统一的加载 / 错误 / 空态。页面不要再各写一份“加载中…”。
 * 用法：<state-view :loading="loading" :empty="!rows.length" empty-text="还没有记录"> ... </state-view>
 */
defineProps<{
  loading?: boolean
  error?: string
  empty?: boolean
  emptyText?: string
}>()

defineEmits<{
  (e: 'retry'): void
}>()
</script>

<template>
  <view v-if="loading" class="state">
    <view class="spinner" />
    <text class="state-text">加载中…</text>
  </view>
  <view v-else-if="error" class="state">
    <text class="state-text danger">{{ error }}</text>
    <button class="btn btn-ghost btn-sm retry" @click="$emit('retry')">重试</button>
  </view>
  <view v-else-if="empty" class="state">
    <text class="state-text">{{ emptyText ?? '暂无数据' }}</text>
  </view>
  <slot v-else />
</template>

<style scoped>
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 96rpx 24rpx;
}

.state-text {
  margin-top: 16rpx;
  color: #98a2b3;
  font-size: 26rpx;
}

.retry {
  margin-top: 20rpx;
}

.spinner {
  width: 40rpx;
  height: 40rpx;
  border: 4rpx solid #e6e9ee;
  border-top-color: #2b6de5;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>

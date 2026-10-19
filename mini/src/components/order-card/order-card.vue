<script setup lang="ts">
import type { OrderView } from '@/api/schema-mini'
import { fmtSize, fmtTime, pointsToYuan } from '@/utils/format'
import { isOrderActive, orderStatusText, orderStatusTone } from '@/utils/dict'

defineProps<{
  order: OrderView
}>()

defineEmits<{
  (e: 'tap'): void
}>()
</script>

<template>
  <view class="order-card" @click="$emit('tap')">
    <view class="row">
      <status-tag :text="orderStatusText(order.status)" :tone="orderStatusTone(order.status)" />
      <text class="faint">{{ fmtTime(order.createTime) }}</text>
    </view>

    <view class="row main">
      <text class="slot">{{ order.cabinetNo ?? '—' }} · {{ order.slotNo ?? '—' }} 号格</text>
      <text class="muted">{{ fmtSize(order.sizeType) }}</text>
    </view>

    <view class="row meta">
      <text v-if="isOrderActive(order.status)" class="muted">
        开始 {{ fmtTime(order.startedAt) }}
      </text>
      <text v-else class="muted">已结算 {{ pointsToYuan(order.settledPoints) }} 元</text>
      <text v-if="order.arrearsPoints && order.arrearsPoints !== '0'" class="danger">
        欠费 {{ pointsToYuan(order.arrearsPoints) }} 元
      </text>
    </view>

    <view v-if="order.leftoverRetrievable" class="leftover">
      有遗留物待取回，点此处理 ›
    </view>
  </view>
</template>

<style scoped>
.order-card {
  background: #ffffff;
  border-radius: 20rpx;
  padding: 26rpx 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 4rpx 16rpx rgba(31, 39, 51, 0.04);
}

.main {
  margin-top: 16rpx;
}

.slot {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
}

.meta {
  margin-top: 10rpx;
}

.leftover {
  margin-top: 16rpx;
  padding: 14rpx 18rpx;
  background: #ecf6e8;
  border-radius: 10rpx;
  color: #3d8b2f;
  font-size: 24rpx;
}
</style>

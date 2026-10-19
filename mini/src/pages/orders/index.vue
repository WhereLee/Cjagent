<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { fetchMyOrders } from '@/api'
import type { OrderView } from '@/api/schema-mini'
import { isLoggedIn } from '@/stores/auth'
import { isOrderActive } from '@/utils/dict'
import { toastError } from '@/utils/format'

const orders = ref<OrderView[]>([])
const loading = ref(false)
const error = ref('')

const activeOrders = computed(() => orders.value.filter((order) => isOrderActive(order.status)))
const historyOrders = computed(() => orders.value.filter((order) => !isOrderActive(order.status)))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    orders.value = await fetchMyOrders(50)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    toastError(e)
  } finally {
    loading.value = false
  }
}

onShow(() => {
  if (!isLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/index' })
    return
  }
  load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})

function goDetail(order: OrderView): void {
  uni.navigateTo({ url: `/pages/order-detail/index?orderNo=${order.orderNo}` })
}

function goHome(): void {
  uni.switchTab({ url: '/pages/home/index' })
}
</script>

<template>
  <view class="page">
    <state-view
      :loading="loading && !orders.length"
      :error="error"
      :empty="!loading && !orders.length"
      empty-text="还没有寄存单，去首页选个点位吧"
      @retry="load"
    >
      <template v-if="activeOrders.length">
        <view class="section-title">进行中</view>
        <order-card
          v-for="order in activeOrders"
          :key="order.orderNo"
          :order="order"
          @tap="goDetail(order)"
        />
      </template>
      <template v-else>
        <view class="section-title">进行中</view>
        <view class="empty-hint">
          当前没有进行中的订单。
          <text class="link" @click="goHome">去首页存件 ›</text>
        </view>
      </template>

      <template v-if="historyOrders.length">
        <view class="section-title">历史订单</view>
        <order-card
          v-for="order in historyOrders"
          :key="order.orderNo"
          :order="order"
          @tap="goDetail(order)"
        />
      </template>
    </state-view>
  </view>
</template>

<style scoped>
.empty-hint {
  padding: 24rpx 8rpx;
  color: #6b7684;
  font-size: 25rpx;
}

.link {
  color: #2b6de5;
}
</style>

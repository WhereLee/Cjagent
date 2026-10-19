<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { fetchWallet, recharge } from '@/api'
import type { WalletView } from '@/api/schema-mini'
import { isLoggedIn } from '@/stores/auth'
import { newRequestId, pointsToYuan, toastError } from '@/utils/format'

const wallet = ref<WalletView | null>(null)
const loading = ref(false)
const error = ref('')
const pointsText = ref('1000')
const charging = ref(false)

/** 同一“充值意图”重试复用同一幂等键；成功后换新 */
let requestId = newRequestId()

const quickAmounts = [10, 20, 50, 100]
const points = computed(() => {
  const value = Math.floor(Number(pointsText.value))
  return Number.isFinite(value) && value > 0 ? value : 0
})
const arrears = computed(() => Number(wallet.value?.arrearsPoints ?? 0))
const unpaid = computed(() => Number(wallet.value?.unpaidOrders ?? 0))
const active = computed(() => Number(wallet.value?.activeOrders ?? 0))
const frozen = computed(() => Number(wallet.value?.frozenPoints ?? 0))
const deposit = computed(() => Number(wallet.value?.depositPoints ?? 0))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    wallet.value = await fetchWallet()
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

function pickAmount(yuan: number): void {
  pointsText.value = String(yuan * 100)
}

async function onRecharge(): Promise<void> {
  if (points.value <= 0) {
    uni.showToast({ title: '请输入正整数点数（1 点 = 1 分）', icon: 'none' })
    return
  }
  if (points.value > 100000) {
    uni.showToast({ title: '单笔最多 100000 点（1000 元）', icon: 'none' })
    return
  }
  charging.value = true
  try {
    await recharge({ requestId, points: String(points.value) })
    uni.showToast({ title: `充值成功 ${pointsToYuan(points.value)} 元`, icon: 'success' })
    requestId = newRequestId()
    await load()
  } catch (e) {
    toastError(e)
  } finally {
    charging.value = false
  }
}

function goOrders(): void {
  uni.switchTab({ url: '/pages/orders/index' })
}
</script>

<template>
  <view class="page">
    <view v-if="error" class="card error-bar">
      <text class="danger">{{ error }}</text>
      <button class="btn btn-ghost btn-sm" @click="load">重试</button>
    </view>

    <view class="card balance-card">
      <text class="balance-label">可用余额</text>
      <text class="balance-value">{{ pointsToYuan(wallet?.points) }} 元</text>
      <view class="balance-meta">
        <text>进行中订单 {{ active }}</text>
        <text>待补款订单 {{ unpaid }}</text>
      </view>
      <view v-if="frozen > 0 || deposit > 0" class="balance-sub">
        冻结 {{ pointsToYuan(wallet?.frozenPoints) }} 元
        <text v-if="deposit > 0"> · 押金栏 {{ pointsToYuan(wallet?.depositPoints) }} 元</text>
      </view>
      <view v-if="arrears > 0" class="arrears">
        有欠费 {{ pointsToYuan(wallet?.arrearsPoints) }} 元，会拦住新下单。
        <text class="arrears-link" @click="goOrders">查看欠费订单 ›</text>
      </view>
    </view>

    <view class="card">
      <view class="card-title">充值</view>
      <view class="muted hint">1 元 = 100 点，1 点 = 1 分；单笔最多 100000 点（1000 元）。</view>

      <view class="amount-row">
        <view
          v-for="amount in quickAmounts"
          :key="amount"
          class="amount-item"
          :class="{ active: points === amount * 100 }"
          @click="pickAmount(amount)"
        >
          {{ amount }} 元
        </view>
      </view>

      <view class="custom-row">
        <input
          v-model="pointsText"
          class="input"
          type="number"
          placeholder="自定义点数（整数）"
          placeholder-class="input-placeholder"
        />
        <text class="faint custom-tip">= {{ pointsToYuan(points) }} 元</text>
      </view>

      <button class="btn btn-primary btn-block recharge-btn" :loading="charging" @click="onRecharge">
        充值 {{ pointsToYuan(points) }} 元
      </button>
    </view>

    <view class="card">
      <view class="card-title">关于费用</view>
      <view class="line">
        <text>下单预收</text>
        <text class="line-value">该格口 4 小时费用</text>
      </view>
      <view class="line">
        <text>取件结算</text>
        <text class="line-value">按实际时长，多退少补</text>
      </view>
      <view class="line">
        <text>欠费</text>
        <text class="line-value">补缴后才能继续下单</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.balance-card {
  background: linear-gradient(135deg, #2b6de5, #5b8ff0);
  color: #ffffff;
}

.balance-label {
  font-size: 24rpx;
  opacity: 0.86;
}

.balance-value {
  display: block;
  margin-top: 10rpx;
  font-size: 64rpx;
  font-weight: 700;
}

.balance-meta {
  display: flex;
  gap: 32rpx;
  margin-top: 16rpx;
  font-size: 24rpx;
  opacity: 0.92;
}

.balance-sub {
  margin-top: 10rpx;
  font-size: 23rpx;
  opacity: 0.86;
}

.arrears {
  margin-top: 16rpx;
  padding: 14rpx 18rpx;
  background: rgba(255, 255, 255, 0.18);
  border-radius: 10rpx;
  font-size: 24rpx;
  line-height: 1.6;
}

.arrears-link {
  color: #ffffff;
  text-decoration: underline;
}

.hint {
  margin-top: 8rpx;
  line-height: 1.7;
}

.amount-row {
  display: flex;
  gap: 14rpx;
  margin-top: 22rpx;
}

.amount-item {
  flex: 1;
  text-align: center;
  padding: 18rpx 0;
  background: #f4f6f9;
  border-radius: 12rpx;
  font-size: 27rpx;
  color: #4a5562;
}

.amount-item.active {
  background: #eef4ff;
  color: #2b6de5;
  font-weight: 600;
}

.custom-row {
  margin-top: 18rpx;
}

.custom-tip {
  display: block;
  margin-top: 10rpx;
  text-align: right;
}

.recharge-btn {
  margin-top: 22rpx;
}
</style>

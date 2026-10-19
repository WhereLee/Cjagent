<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { createOrder, fetchAvailability, fetchWallet } from '@/api'
import type { Availability, WalletView } from '@/api/schema-mini'
import { ApiError } from '@/api/http'
import { fmtSize, newRequestId, pointsToYuan, toastError } from '@/utils/format'

const CABINET_KEY = 'cabinet-mini-order-result'

const cabinetNo = ref('')
const siteCode = ref('')
const availability = ref<Availability | null>(null)
const loadingFree = ref(false)
const freeError = ref('')
const wallet = ref<WalletView | null>(null)

const SIZE_ORDER = ['SMALL', 'MEDIUM', 'LARGE']
const sizes = computed(() => {
  const list = [...(availability.value?.sizes ?? [])]
  return list.sort(
    (a, b) => SIZE_ORDER.indexOf(String(a.sizeType)) - SIZE_ORDER.indexOf(String(b.sizeType)),
  )
})

const form = reactive({
  sizeType: 'MEDIUM',
  estimateMinutes: 240,
})
const customMinutes = ref('')
const useCustomMinutes = ref(false)
const submitting = ref(false)

/** 同一“下单意图”重试复用同一个 requestId；只有后端明确拒绝（确定没建单）才换新 */
let requestId = newRequestId()
const DEFINITE_REJECT_CODES = new Set([10000, 10404, 10409, 10410, 10412, 40000])

const durationOptions = [
  { label: '1 小时', value: 60 },
  { label: '4 小时', value: 240 },
  { label: '8 小时', value: 480 },
  { label: '1 天', value: 1440 },
]

function freeOf(sizeType: string): number {
  const item = sizes.value.find((entry) => String(entry.sizeType) === sizeType)
  return Number(item?.free ?? 0)
}

function pickSize(sizeType: string): void {
  if (freeOf(sizeType) <= 0) {
    uni.showToast({ title: '这个尺寸暂时没有空位', icon: 'none' })
    return
  }
  form.sizeType = sizeType
}

function pickDuration(minutes: number): void {
  useCustomMinutes.value = false
  form.estimateMinutes = minutes
}

function onCustomInput(): void {
  useCustomMinutes.value = true
  const value = Math.floor(Number(customMinutes.value))
  if (Number.isFinite(value)) form.estimateMinutes = value
}

async function loadAvailability(): Promise<void> {
  if (!cabinetNo.value) return
  loadingFree.value = true
  freeError.value = ''
  try {
    availability.value = await fetchAvailability(cabinetNo.value)
    // 默认选中的尺寸没余位时，自动落到第一个有位的尺寸（只改选择，不改规则）
    if (freeOf(form.sizeType) <= 0) {
      const firstFree = sizes.value.find((item) => Number(item.free ?? 0) > 0)
      if (firstFree?.sizeType) form.sizeType = String(firstFree.sizeType)
    }
  } catch (e) {
    freeError.value = e instanceof Error ? e.message : '余位查询失败'
    toastError(e)
  } finally {
    loadingFree.value = false
  }
}

onLoad((query) => {
  cabinetNo.value = String(query?.cabinetNo ?? '')
  siteCode.value = String(query?.siteCode ?? '')
  loadAvailability()
  // 余额是只读提示：没钱时尽早告诉用户，不要在点了下单后才被拒
  fetchWallet()
    .then((view) => {
      wallet.value = view
    })
    .catch(() => {
      wallet.value = null
    })
})

async function onSubmit(): Promise<void> {
  if (!cabinetNo.value) return
  const minutes = Math.floor(Number(form.estimateMinutes))
  if (!Number.isFinite(minutes) || minutes < 15 || minutes > 4320) {
    uni.showToast({ title: '预计时长需在 15 分钟到 3 天之间', icon: 'none' })
    return
  }
  if (freeOf(form.sizeType) <= 0) {
    uni.showToast({ title: '所选尺寸没有空位，请换一个尺寸', icon: 'none' })
    return
  }

  submitting.value = true
  try {
    const view = await createOrder({
      requestId,
      cabinetNo: cabinetNo.value,
      sizeType: form.sizeType,
      estimateMinutes: minutes,
    })
    uni.setStorageSync(`${CABINET_KEY}-${view.orderNo}`, view)
    requestId = newRequestId()
    uni.redirectTo({ url: `/pages/order-result/index?orderNo=${view.orderNo ?? ''}` })
  } catch (e) {
    // 网络错误/超时/409 不能换 requestId：服务端可能已经建单，换新等于把幂等保护扔掉。
    // 只有后端明确拒绝（余额不足/无格口/参数错等）才能确认没建单，此时换新意图。
    if (e instanceof ApiError && DEFINITE_REJECT_CODES.has(e.code)) {
      requestId = newRequestId()
    }
    handleFailure(e)
  } finally {
    submitting.value = false
  }
}

function handleFailure(e: unknown): void {
  if (!(e instanceof ApiError)) {
    toastError(e)
    return
  }
  if (e.code === 40900) {
    uni.showModal({
      title: '请勿重复提交',
      content: '这次下单请求已经提交，请到“我的寄存单”查看结果，不要重复点击。',
      confirmText: '去查看',
      cancelText: '知道了',
      success: (res) => {
        if (res.confirm) uni.switchTab({ url: '/pages/orders/index' })
      },
    })
    return
  }
  if (e.code === 10409 || e.code === 10410) {
    uni.showModal({
      title: '格口刚刚被占用',
      content: e.message || '该尺寸暂时没有空位，换一个尺寸或换一台柜机试试。',
      showCancel: false,
    })
    loadAvailability()
    return
  }
  if (e.code === 10412) {
    uni.showModal({
      title: '余额不足',
      content: e.message,
      confirmText: '去充值',
      cancelText: '稍后',
      success: (res) => {
        if (res.confirm) uni.switchTab({ url: '/pages/wallet/index' })
      },
    })
    return
  }
  if (e.code === 10000 && e.message.includes('未结束的寄存单')) {
    uni.showModal({
      title: '你还有进行中的订单',
      content: e.message,
      confirmText: '去查看',
      cancelText: '知道了',
      success: (res) => {
        if (res.confirm) uni.switchTab({ url: '/pages/orders/index' })
      },
    })
    return
  }
  toastError(e)
}

function goBack(): void {
  uni.navigateBack()
}
</script>

<template>
  <view class="page has-footer">
    <view class="card">
      <view class="row">
        <text class="title">柜机 {{ cabinetNo }}</text>
        <text v-if="siteCode" class="muted">点位 {{ siteCode }}</text>
      </view>
      <view class="muted intro">
        下单会占格并预收该格口 4 小时费用，同时打开柜门。取件时按实际存放时长结算，多退少补。
      </view>

      <view v-if="freeError" class="err-box">
        <text class="danger">{{ freeError }}</text>
        <button class="btn btn-ghost btn-sm" @click="loadAvailability">重试</button>
      </view>
    </view>

    <view class="card">
      <view class="card-title">选择尺寸</view>
      <view class="muted hint">小格最省，大格放行李箱；看到剩余格数再选，抢光会提示换一个。</view>
      <view class="size-row">
        <view
          v-for="item in sizes"
          :key="item.sizeType"
          class="size-item"
          :class="{
            active: form.sizeType === item.sizeType,
            disabled: Number(item.free ?? 0) <= 0,
          }"
          @click="pickSize(String(item.sizeType))"
        >
          <text class="size-name">{{ fmtSize(item.sizeType) }}</text>
          <text class="size-free">
            {{ Number(item.free ?? 0) > 0 ? `剩 ${item.free} 格` : '已满' }}
          </text>
        </view>
      </view>
      <view v-if="!loadingFree && !sizes.length" class="faint hint">这台柜机暂时没有可查询的格口</view>
    </view>

    <view class="card">
      <view class="card-title">预计存放多久</view>
      <view class="muted hint">仅用于记录；实际以取件时按存放时长结算为准。</view>
      <view class="duration-row">
        <view
          v-for="item in durationOptions"
          :key="item.value"
          class="duration-item"
          :class="{ active: !useCustomMinutes && form.estimateMinutes === item.value }"
          @click="pickDuration(item.value)"
        >
          {{ item.label }}
        </view>
      </view>
      <view class="custom-row">
        <input
          v-model="customMinutes"
          class="input custom-input"
          type="number"
          placeholder="自定义分钟数（15 ~ 4320）"
          placeholder-class="input-placeholder"
          @input="onCustomInput"
        />
      </view>
    </view>

    <view class="card">
      <view class="card-title">费用说明</view>
      <view class="line">
        <text>预收</text>
        <text class="line-value">该格口 4 小时费用</text>
      </view>
      <view class="line">
        <text>结算</text>
        <text class="line-value">取件时按实际时长，多退少补</text>
      </view>
      <view class="line">
        <text>取消</text>
        <text class="line-value">下单即开门，没有取消入口</text>
      </view>
      <view class="line">
        <text>当前余额</text>
        <text class="line-value">{{ wallet ? `${pointsToYuan(wallet.points)} 元` : '—' }}</text>
      </view>
      <view class="muted hint">
        具体单价以你的账单为准；余额不足会下单失败，可先去钱包充值。
      </view>
    </view>

    <view class="footer-holder" />
    <view class="footer-bar">
      <button class="btn btn-primary btn-block" :loading="submitting" @click="onSubmit">
        确认下单并开门
      </button>
      <button class="btn btn-plain btn-block back-btn" @click="goBack">再想想</button>
    </view>
  </view>
</template>

<style scoped>
.has-footer {
  padding-bottom: 24rpx;
}

.intro {
  margin-top: 12rpx;
  line-height: 1.7;
}

.err-box {
  margin-top: 18rpx;
  padding: 16rpx 18rpx;
  background: #fdeeea;
  border-radius: 12rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.hint {
  margin-top: 10rpx;
  line-height: 1.7;
}

.size-row {
  display: flex;
  gap: 16rpx;
  margin-top: 22rpx;
}

.size-item {
  flex: 1;
  border: 2rpx solid #e6e9ee;
  border-radius: 14rpx;
  padding: 24rpx 0;
  text-align: center;
  background: #fafbfc;
}

.size-item.active {
  border-color: #2b6de5;
  background: #eef4ff;
}

.size-item.disabled {
  opacity: 0.45;
}

.size-name {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
}

.size-free {
  display: block;
  margin-top: 8rpx;
  color: #6b7684;
  font-size: 23rpx;
}

.duration-row {
  display: flex;
  gap: 12rpx;
  margin-top: 22rpx;
  flex-wrap: wrap;
}

.duration-item {
  padding: 14rpx 26rpx;
  border-radius: 12rpx;
  background: #f4f6f9;
  color: #4a5562;
  font-size: 26rpx;
}

.duration-item.active {
  background: #eef4ff;
  color: #2b6de5;
  font-weight: 600;
}

.custom-row {
  margin-top: 16rpx;
}

.custom-input {
  height: 80rpx;
}

.back-btn {
  margin-top: 8rpx;
  font-size: 26rpx;
}
</style>

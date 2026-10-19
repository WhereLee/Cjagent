<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { doorAction, fetchBill, fetchVoucher } from '@/api'
import type { BillView, StorageOrderView, VoucherView } from '@/api/schema-mini'
import { ApiError } from '@/api/http'
import { orderStatusText, orderStatusTone } from '@/utils/dict'
import { fmtSize, pointsToYuan, toastError } from '@/utils/format'

const RESULT_KEY = 'cabinet-mini-order-result'

const orderNo = ref('')
const created = ref<StorageOrderView | null>(null)
const bill = ref<BillView | null>(null)
const voucher = ref<VoucherView | null>(null)
const loading = ref(true)
const closing = ref(false)
/** 用户在这次结果页里已经点过“我已关门” */
const closedDone = ref(false)

const status = computed(() => bill.value?.status ?? created.value?.status ?? '')
const allocatedDiff = computed(() => {
  const allocated = created.value?.allocatedSize
  const ordered = bill.value?.sizeType
  return allocated && ordered && allocated !== ordered ? allocated : ''
})

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    const [billView, voucherView] = await Promise.all([
      fetchBill(orderNo.value),
      fetchVoucher(orderNo.value).catch(() => null),
    ])
    bill.value = billView
    voucher.value = voucherView
  } catch (e) {
    toastError(e)
  } finally {
    loading.value = false
  }
}

onLoad((query) => {
  orderNo.value = String(query?.orderNo ?? '')
  const cached = uni.getStorageSync(`${RESULT_KEY}-${orderNo.value}`)
  if (cached && typeof cached === 'object') created.value = cached as StorageOrderView
  if (orderNo.value) {
    loadDetail().then(() => {
      // 下单回执与账单落库之间有一个短窗口：状态还在开门中时再拉一次，避免页面停在旧状态
      if (status.value === 'RESERVED' || status.value === 'OPENING') {
        setTimeout(loadDetail, 2000)
      }
    })
  }
})

/** 关门校验：只确认门关上了，不改变计费起点（计费从开柜那一刻开始） */
async function onCloseDoor(): Promise<void> {
  closing.value = true
  try {
    const view = await doorAction(orderNo.value, 'CLOSE_VERIFY')
    closedDone.value = true
    if (view.message) {
      uni.showModal({ title: '提示', content: view.message, showCancel: false })
    } else {
      uni.showToast({ title: '已确认关门', icon: 'success' })
    }
    await loadDetail()
  } catch (e) {
    if (e instanceof ApiError && e.message.includes('没有待关闭的柜门')) {
      closedDone.value = true
      uni.showToast({ title: '柜门已经是关好的', icon: 'success' })
    } else if (e instanceof ApiError && e.message.includes('柜门')) {
      uni.showModal({
        title: '柜门还没有关好',
        content: `${e.message}。请把门关紧后再点一次。`,
        showCancel: false,
      })
    } else {
      toastError(e)
    }
  } finally {
    closing.value = false
  }
}

function copyCode(): void {
  const code = voucher.value?.voucherCode ?? ''
  if (!code) return
  uni.setClipboardData({
    data: code,
    success: () => uni.showToast({ title: '取件码已复制', icon: 'success' }),
  })
}

function goDetail(): void {
  uni.redirectTo({ url: `/pages/order-detail/index?orderNo=${orderNo.value}` })
}

function goHome(): void {
  uni.switchTab({ url: '/pages/home/index' })
}
</script>

<template>
  <view class="page">
    <view class="card success-card">
      <view class="row">
        <status-tag :text="orderStatusText(status)" :tone="orderStatusTone(status)" />
        <text class="faint">订单 {{ orderNo }}</text>
      </view>

      <view class="slot-line">
        <text class="slot-no">{{ created?.slotNo ?? voucher?.slotNo ?? '—' }}</text>
        <text class="muted">号格</text>
      </view>
      <view class="muted">柜机 {{ created?.cabinetNo ?? voucher?.cabinetNo ?? '—' }}</view>

      <view v-if="allocatedDiff" class="allocated">
        实际分配：{{ fmtSize(allocatedDiff) }}（你选的尺寸已满，系统给了更大的格口，费用以账单为准）
      </view>

      <view v-if="bill" class="paid-line">
        已预收 <text class="strong">{{ pointsToYuan(bill.prepayPoints) }} 元</text>
        <text class="faint">，取件时按实际时长多退少补</text>
      </view>
    </view>

    <!-- 下单即开门：这一步是“把东西放进去、把门关上”，不是开始计时 -->
    <view class="card close-card">
      <view class="card-title">{{ closedDone ? '已确认关门' : '放入物品后请关好柜门' }}</view>
      <view v-if="!closedDone" class="muted step-text">
        柜门已经打开。放入物品后把门关紧，再点下面的按钮确认。
        费用从开柜那一刻开始计算，头 30 分钟免费，关门不影响计费起点。
      </view>
      <button
        v-if="!closedDone"
        class="btn btn-primary btn-block close-btn"
        :loading="closing"
        @click="onCloseDoor"
      >
        我已放好并关门
      </button>
      <view v-else class="muted done-tip">
        已记录关门。订单进入计费中，随时可在“订单”里查看或取件。
      </view>
    </view>

    <view class="card voucher-card">
      <view class="row">
        <text class="card-title">取件码</text>
        <text class="faint">请记下或截图</text>
      </view>
      <view class="code-row">
        <text class="code" @click="copyCode">{{ voucher?.voucherCode ?? '—' }}</text>
        <button class="btn btn-ghost btn-sm" @click="copyCode">复制</button>
      </view>
      <view class="muted code-hint">
        手机不在身边时，可在柜机屏上输入取件码 + 手机号后四位取件。连错 5 次会锁定开柜，可在订单详情里重置取件码。
      </view>
      <view v-if="!voucher && !loading" class="faint">取件码暂不可用，可到订单详情里查看或联系客服。</view>
    </view>

    <view v-if="created?.message" class="card">
      <view class="card-title">须知</view>
      <text class="text">{{ created.message }}</text>
    </view>

    <view class="actions">
      <button class="btn btn-primary btn-block" @click="goDetail">查看订单详情</button>
      <button class="btn btn-plain btn-block" @click="goHome">回首页</button>
    </view>
  </view>
</template>

<style scoped>
.success-card {
  background: linear-gradient(180deg, #f7fbff, #ffffff);
}

.slot-line {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  margin: 24rpx 0 6rpx;
}

.slot-no {
  font-size: 96rpx;
  font-weight: 700;
  color: #2b6de5;
  line-height: 1.1;
}

.allocated {
  margin-top: 18rpx;
  padding: 14rpx 18rpx;
  background: #fdf3e7;
  border-radius: 10rpx;
  color: #a86413;
  font-size: 24rpx;
  line-height: 1.6;
}

.paid-line {
  margin-top: 18rpx;
  font-size: 26rpx;
}

.close-card {
  border: 2rpx solid #dbe6ff;
}

.step-text {
  margin-top: 10rpx;
  line-height: 1.8;
}

.close-btn {
  margin-top: 24rpx;
}

.done-tip {
  margin-top: 14rpx;
  line-height: 1.7;
}

.voucher-card {
  border: 2rpx solid #2b6de5;
}

.code-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 16rpx;
}

.code {
  font-size: 72rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
  color: #2b6de5;
}

.code-hint {
  margin-top: 14rpx;
  line-height: 1.7;
}

.actions {
  margin-top: 8rpx;
}

.actions .btn + .btn {
  margin-top: 8rpx;
}
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  doorAction,
  fetchBill,
  fetchMyOrders,
  fetchVoucher,
  leftoverOpen,
  leftoverTaken,
  pickup,
  reportLeftover,
  resetVoucher,
} from '@/api'
import type { BillView, OrderView, VoucherView } from '@/api/schema-mini'
import { ApiError } from '@/api/http'
import { isLoggedIn } from '@/stores/auth'
import { billLineTypeText, isOrderActive, orderStatusHint, orderStatusText, orderStatusTone } from '@/utils/dict'
import { elapsedText, fmtSize, fmtTime, pointsToYuan, toastError } from '@/utils/format'

const orderNo = ref('')
const bill = ref<BillView | null>(null)
const voucher = ref<VoucherView | null>(null)
const myOrder = ref<OrderView | null>(null)
const lastMessage = ref('')
const loading = ref(false)
const acting = ref(false)

const status = computed(() => bill.value?.status ?? '')
const alive = computed(() => isOrderActive(status.value))
const leftoverRetrievable = computed(() => Boolean(myOrder.value?.leftoverRetrievable))

/** 把“预收/应收/已结算/流水”翻译成一句人话，避免用户以为“已结算 0 元”是异常 */
const settlementText = computed(() => {
  if (!bill.value) return ''
  const arrears = Number(bill.value.arrearsPoints ?? 0)
  const settled = Number(bill.value.settledPoints ?? 0)
  const refunded = (bill.value.lines ?? []).some((line) => line.type === 'PREPAY_REFUND')
  if (arrears > 0) return `本次仍有欠费 ${pointsToYuan(bill.value.arrearsPoints)} 元，可联系客服处理`
  if (settled > 0) return `本次实际扣费 ${pointsToYuan(bill.value.settledPoints)} 元`
  if (refunded) return '本次没有产生费用，预收已全额退回'
  return ''
})

/** 主操作：按订单状态给出唯一一件“现在该做的事”，不把后端动作平铺给用户 */
const primaryAction = computed<'REOPEN' | 'PICKUP' | 'CLOSE' | 'SUPPORT' | 'WAIT' | 'NONE'>(() => {
  switch (status.value) {
    case 'INIT':
    case 'RESERVED':
      return 'REOPEN'
    case 'OPENING':
    case 'SETTLING':
    case 'STORED':
      return 'WAIT'
    case 'ACTIVE':
    case 'TEMP_OPEN':
    case 'EXPIRED':
      return 'PICKUP'
    case 'ABNORMAL':
      return 'SUPPORT'
    default:
      return 'NONE'
  }
})

async function load(): Promise<void> {
  if (!orderNo.value) return
  loading.value = true
  try {
    const [billView, orders] = await Promise.all([
      fetchBill(orderNo.value),
      fetchMyOrders(50).catch(() => [] as OrderView[]),
    ])
    bill.value = billView
    myOrder.value = (orders ?? []).find((order) => order.orderNo === orderNo.value) ?? null
    try {
      voucher.value = await fetchVoucher(orderNo.value)
    } catch {
      voucher.value = null // 终态单的取件码已失效，属正常
    }
  } catch (e) {
    toastError(e)
  } finally {
    loading.value = false
  }
}

onLoad((query) => {
  orderNo.value = String(query?.orderNo ?? '')
})

onShow(() => {
  if (!isLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/index' })
    return
  }
  load()
})

async function runAction(fn: () => Promise<unknown>, successText: string): Promise<void> {
  acting.value = true
  lastMessage.value = ''
  try {
    const view = await fn()
    // 动作返回 StorageOrderView 时带 message；遗留物接口只回一句字符串
    const message =
      view && typeof view === 'object' && 'message' in view
        ? String((view as { message?: string }).message ?? '')
        : typeof view === 'string'
          ? view
          : ''
    if (message) lastMessage.value = message
    uni.showToast({ title: successText, icon: 'success' })
    await load()
  } catch (e) {
    if (e instanceof ApiError) {
      // 门已经关好时再点“确认关门”会得到这条业务拒绝：它不是错误，而是事实
      if (e.message.includes('没有待关闭的柜门')) {
        uni.showToast({ title: '柜门已经是关好的', icon: 'success' })
        await load()
        return
      }
      uni.showModal({ title: '操作没有完成', content: e.message, showCancel: false })
    } else {
      toastError(e)
    }
  } finally {
    acting.value = false
  }
}

function onReopen(): void {
  runAction(() => doorAction(orderNo.value, 'OPEN'), '开门指令已下发')
}

function onCloseVerify(): void {
  runAction(() => doorAction(orderNo.value, 'CLOSE_VERIFY'), '已确认关门')
}

function onTempOpen(): void {
  uni.showModal({
    title: '中途取物',
    content: '会打开柜门，柜口继续为你保留；取完请把门关好。',
    success: (res) => {
      if (res.confirm) runAction(() => doorAction(orderNo.value, 'OPEN_TEMP'), '柜门已打开')
    },
  })
}

function onPickup(): void {
  uni.showModal({
    title: '取件并结束订单',
    content: '会按实际存放时长结算（预收多退少补）并打开柜门。确认取件？',
    success: (res) => {
      if (res.confirm) runAction(() => pickup(orderNo.value), '取件完成，请取走物品并关门')
    },
  })
}

function onReportLeftover(): void {
  uni.showModal({
    title: '格口里有别人的东西？',
    content: '确认后会免费退回你这单并锁定格口，工作人员会联系遗留物主人。请只在确实不是你的物品时上报。',
    success: (res) => {
      if (res.confirm) runAction(() => reportLeftover(orderNo.value), '已上报')
    },
  })
}

function onLeftoverOpen(): void {
  runAction(() => leftoverOpen(orderNo.value), '柜门已打开，请取走遗留物')
}

function onLeftoverTaken(): void {
  uni.showModal({
    title: '确认取完',
    content: '确认遗留物已经取走，格口恢复正常？',
    success: (res) => {
      if (res.confirm) runAction(() => leftoverTaken(orderNo.value), '已确认取完')
    },
  })
}

function copyCode(): void {
  const code = voucher.value?.voucherCode ?? ''
  if (!code) return
  uni.setClipboardData({ data: code, success: () => uni.showToast({ title: '已复制', icon: 'success' }) })
}

function onResetVoucher(): void {
  uni.showModal({
    title: '重置取件码',
    content: '旧码立即作废，错次清零、连错锁定一并解除。确认重置？',
    success: (res) => {
      if (!res.confirm) return
      acting.value = true
      resetVoucher(orderNo.value)
        .then((view) => {
          voucher.value = view
          uni.showToast({ title: '已换新码', icon: 'success' })
        })
        .catch(toastError)
        .finally(() => {
          acting.value = false
        })
    },
  })
}

function goSupport(): void {
  uni.navigateTo({ url: `/pages/support/index?orderNo=${orderNo.value}` })
}

function fmtLineAmount(amount: string | number | undefined): string {
  const value = pointsToYuan(amount)
  if (value === '—') return value
  return value.startsWith('-') ? value : `+${value}`
}
</script>

<template>
  <view class="page">
    <state-view :loading="loading && !bill" :empty="!loading && !bill" empty-text="没找到这张寄存单" @retry="load">
      <template v-if="bill">
        <!-- 状态与位置 -->
        <view class="card">
          <view class="row">
            <status-tag :text="orderStatusText(status)" :tone="orderStatusTone(status)" />
            <text class="faint">{{ bill.orderNo }}</text>
          </view>
          <view class="muted hint">{{ orderStatusHint(status) }}</view>

          <view class="slot-row">
            <text class="slot">{{ myOrder?.cabinetNo ?? '—' }} · {{ myOrder?.slotNo ?? '—' }} 号格</text>
            <text class="chip chip-info">{{ fmtSize(bill.sizeType) }}</text>
          </view>

          <view class="line">
            <text>开始计费</text>
            <text class="line-value">{{ fmtTime(bill.startedAt) }}</text>
          </view>
          <view v-if="alive && bill.startedAt" class="line">
            <text>已存放</text>
            <text class="line-value">{{ elapsedText(bill.startedAt) }}（以结算为准）</text>
          </view>
          <view v-if="myOrder?.expectedFinishAt" class="line">
            <text>预计结束</text>
            <text class="line-value">{{ fmtTime(myOrder.expectedFinishAt) }}</text>
          </view>
        </view>

        <!-- 当前该做的事 -->
        <view class="card action-card">
          <view class="card-title">现在要做的</view>

          <template v-if="primaryAction === 'REOPEN'">
            <view class="muted hint">柜门还没有打开。点下面的按钮重新开门；开门前不会开始计费。</view>
            <button class="btn btn-primary btn-block" :loading="acting" @click="onReopen">重新开门</button>
          </template>

          <template v-else-if="primaryAction === 'WAIT'">
            <view class="muted hint">正在等待设备回执，请稍候；可在下方刷新状态。</view>
            <button class="btn btn-ghost btn-block" @click="load">刷新状态</button>
          </template>

          <template v-else-if="primaryAction === 'PICKUP'">
            <view class="muted hint">
              取件会按实际存放时长结算并打开柜门，预收费用多退少补；结束前请确认物品已取走。
            </view>
            <button class="btn btn-primary btn-block" :loading="acting" @click="onPickup">取件结算</button>
            <view class="sub-actions">
              <button v-if="status === 'ACTIVE'" class="btn btn-ghost btn-sm" @click="onTempOpen">
                中途开柜取物
              </button>
              <button
                v-if="status === 'ACTIVE' || status === 'TEMP_OPEN'"
                class="btn btn-ghost btn-sm"
                @click="onCloseVerify"
              >
                门没关好？确认关门
              </button>
            </view>
          </template>

          <template v-else-if="primaryAction === 'SUPPORT'">
            <view class="muted hint">这张单状态异常，需要人工核对。请把订单号发给客服。</view>
            <button class="btn btn-primary btn-block" @click="goSupport">联系 AI 客服</button>
          </template>

          <template v-else>
            <view class="muted hint">这张单已经结束，不需要再操作。</view>
          </template>

          <view v-if="lastMessage" class="message-box">{{ lastMessage }}</view>
        </view>

        <!-- 遗留物：仅当后端标记“可取回”时出现 -->
        <view v-if="leftoverRetrievable" class="card leftover-card">
          <view class="card-title">你的遗留物待取回</view>
          <view class="muted hint">
            这张单结束后，格口里检测到你的物品。可以开柜取回；取完后点“我取完了”，格口才会恢复可用。
          </view>
          <view class="sub-actions">
            <button class="btn btn-primary btn-sm" :loading="acting" @click="onLeftoverOpen">开门取回</button>
            <button class="btn btn-ghost btn-sm" @click="onLeftoverTaken">我取完了</button>
          </view>
        </view>

        <!-- 活单：发现格口里有别人的东西 -->
        <view v-if="alive && !leftoverRetrievable" class="card">
          <view class="card-title">格口里有别人的东西？</view>
          <view class="muted hint">
            刚打开柜门就发现里面有物品，不是你的。上报后本单免费退回、格口锁定，工作人员会联系物主。
          </view>
          <button class="btn btn-danger btn-block report-btn" @click="onReportLeftover">上报遗留物</button>
        </view>

        <!-- 取件码 -->
        <view v-if="voucher" class="card voucher-card">
          <view class="row">
            <text class="card-title">取件码</text>
            <text class="faint">柜机屏取件用</text>
          </view>
          <view class="code-row">
            <text class="code" @click="copyCode">{{ voucher.voucherCode ?? '—' }}</text>
            <button class="btn btn-ghost btn-sm" @click="copyCode">复制</button>
          </view>
          <view class="muted code-hint">柜机屏输入取件码 + 手机号后四位即可取件。</view>
          <view v-if="voucher.locked" class="locked">
            取件码连错已锁定开柜。重置取件码可立即解除，或联系客服。
          </view>
          <button class="btn btn-ghost btn-block reset-btn" :loading="acting" @click="onResetVoucher">
            重置取件码（旧码作废）
          </button>
        </view>

        <!-- 账单 -->
        <view class="card">
          <view class="row">
            <text class="card-title">账单明细</text>
            <text class="faint">单价 {{ pointsToYuan(bill.unitPointsPerHour) }} 元/时</text>
          </view>

          <view class="line">
            <text>计费时长</text>
            <text class="line-value">{{ bill.billedMinutes ?? 0 }} 分钟（{{ bill.billedHours ?? 0 }} 个计费小时）</text>
          </view>
          <view class="line">
            <text>免费时长</text>
            <text class="line-value">{{ bill.freeMinutes ?? 0 }} 分钟</text>
          </view>
          <view class="line">
            <text>封顶规则</text>
            <text class="line-value">{{ bill.dailyCapHours ?? 0 }} 小时/天 × {{ bill.capDays ?? 0 }} 天</text>
          </view>

          <view class="divider" />

          <view class="line strong">
            <text>预收</text>
            <text class="line-value">{{ pointsToYuan(bill.prepayPoints) }} 元</text>
          </view>
          <view class="line strong">
            <text>应收</text>
            <text class="line-value">{{ pointsToYuan(bill.chargePoints) }} 元</text>
          </view>
          <view class="line strong">
            <text>已结算</text>
            <text class="line-value">{{ pointsToYuan(bill.settledPoints) }} 元</text>
          </view>
          <view v-if="bill.arrearsPoints && bill.arrearsPoints !== '0'" class="line strong danger">
            <text>欠费</text>
            <text class="line-value">{{ pointsToYuan(bill.arrearsPoints) }} 元</text>
          </view>

          <view v-if="settlementText" class="settle-summary">{{ settlementText }}</view>

          <view class="section-title">资金流水</view>
          <view v-if="!bill.lines?.length" class="faint">暂无流水</view>
          <view v-for="(line, index) in bill.lines" :key="index" class="flow-item">
            <view class="row">
              <text class="text">{{ billLineTypeText(line.type) }}</text>
              <text class="text strong">{{ fmtLineAmount(line.amount) }} 元</text>
            </view>
            <view class="row flow-sub">
              <text class="faint">{{ fmtTime(line.at) }}</text>
              <text class="faint">余额 {{ pointsToYuan(line.balanceAfter) }} 元</text>
            </view>
            <view v-if="line.remark" class="faint remark">{{ line.remark }}</view>
          </view>
        </view>

        <button class="btn btn-plain btn-block" @click="goSupport">对账单有疑问？问 AI 客服 ›</button>
      </template>
    </state-view>
  </view>
</template>

<style scoped>
.hint {
  margin-top: 10rpx;
  line-height: 1.7;
}

.slot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 0 8rpx;
}

.slot {
  font-size: 34rpx;
  font-weight: 600;
}

.action-card {
  border: 2rpx solid #dbe6ff;
}

.action-card .btn {
  margin-top: 20rpx;
}

.sub-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 16rpx;
  flex-wrap: wrap;
}

.message-box {
  margin-top: 18rpx;
  padding: 16rpx 18rpx;
  background: #f7f8fa;
  border-radius: 10rpx;
  color: #4a5562;
  font-size: 24rpx;
  line-height: 1.7;
}

.leftover-card {
  border: 2rpx solid #cfe8c5;
  background: #fbfef9;
}

.report-btn {
  margin-top: 18rpx;
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
  font-size: 64rpx;
  font-weight: 700;
  letter-spacing: 6rpx;
  color: #2b6de5;
}

.code-hint {
  margin-top: 12rpx;
  line-height: 1.7;
}

.locked {
  margin-top: 14rpx;
  padding: 14rpx 18rpx;
  background: #fdeeea;
  border-radius: 10rpx;
  color: #b4471f;
  font-size: 24rpx;
  line-height: 1.6;
}

.reset-btn {
  margin-top: 16rpx;
}

.settle-summary {
  margin: 16rpx 0 4rpx;
  padding: 16rpx 18rpx;
  background: #eef4ff;
  border-radius: 10rpx;
  color: #2b6de5;
  font-size: 25rpx;
  line-height: 1.6;
}

.flow-item {
  padding: 16rpx 0;
  border-bottom: 1px solid #f5f6f8;
}
.flow-item:last-child {
  border-bottom: none;
}

.flow-sub {
  margin-top: 6rpx;
}

.remark {
  margin-top: 6rpx;
  line-height: 1.6;
}
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { confirmWorkOrder, fetchMyWorkOrders, fetchWorkOrder, submitWorkOrderCsat } from '@/api'
import type { WorkOrderCustomerView } from '@/api/contracts'
import { isLoggedIn } from '@/stores/auth'
import {
  canReopenWorkOrder,
  closeReasonText,
  isWorkOrderOpen,
  workOrderCategoryText,
  workOrderPriorityText,
  workOrderStatusText,
  workOrderStatusTone,
} from '@/utils/dict'
import { fmtTime, toastError } from '@/utils/format'

const workOrderId = ref('')
const workOrder = ref<WorkOrderCustomerView | null>(null)
const list = ref<WorkOrderCustomerView[]>([])
const loading = ref(false)
const error = ref('')
const acting = ref(false)
const note = ref('')
const csatScore = ref(0)
const csatComment = ref('')
const csatSubmitting = ref(false)
const csatDismissed = ref(false)

const open = computed(() => isWorkOrderOpen(workOrder.value?.status))
const reopenable = computed(() => canReopenWorkOrder(workOrder.value?.status))
const mergedInto = computed(() => workOrder.value?.mergedIntoId)

/** 只有“用户确认解决”的单才邀请评价；一单一次，已评过不再展示。 */
const canRate = computed(
  () =>
    !!workOrder.value &&
    workOrder.value.status === 'RESOLVED' &&
    workOrder.value.closeReason === 'USER_CONFIRMED' &&
    !workOrder.value.csatScore,
)

onLoad((query) => {
  workOrderId.value = String(query?.id ?? '')
})

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

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (workOrderId.value) {
      workOrder.value = await fetchWorkOrder(workOrderId.value)
    } else {
      list.value = await fetchMyWorkOrders(30)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    toastError(e)
  } finally {
    loading.value = false
  }
}

function goDetail(item: WorkOrderCustomerView): void {
  if (!item.id) return
  uni.navigateTo({ url: `/pages/work-order/index?id=${item.id}` })
}

function goMerged(): void {
  if (!mergedInto.value) return
  uni.redirectTo({ url: `/pages/work-order/index?id=${mergedInto.value}` })
}

function goOrder(): void {
  if (workOrder.value?.orderNo) {
    uni.navigateTo({ url: `/pages/order-detail/index?orderNo=${workOrder.value.orderNo}` })
  }
}

function goSupport(): void {
  const orderNo = workOrder.value?.orderNo
  uni.navigateTo({
    url: orderNo ? `/pages/support/index?orderNo=${orderNo}` : '/pages/support/index',
  })
}

async function submit(solved: boolean): Promise<void> {
  if (!workOrderId.value || acting.value) return
  acting.value = true
  try {
    const id = await confirmWorkOrder(workOrderId.value, {
      solved,
      note: note.value.trim() || undefined,
    })
    uni.showToast({ title: solved ? '已确认解决' : '已反馈，工作人员会继续跟进', icon: 'success' })
    note.value = ''
    // 重开撞上同类未关闭单时后端会合并：直接切到那张，别让用户停在一张已合并的单上
    if (!solved && id && String(id) !== workOrderId.value) {
      workOrderId.value = String(id)
    }
    await load()
  } catch (e) {
    toastError(e)
  } finally {
    acting.value = false
  }
}

function onSolved(): void {
  uni.showModal({
    title: '确认问题已解决？',
    content: '确认后这张工单会关闭，关闭依据记为“用户确认”。',
    success: (res) => {
      if (res.confirm) submit(true)
    },
  })
}

function onReopen(): void {
  uni.showModal({
    title: '问题还没解决？',
    content: '工单会重新打开，工作人员会继续跟进。',
    success: (res) => {
      if (res.confirm) submit(false)
    },
  })
}

async function onSubmitCsat(): Promise<void> {
  if (!workOrderId.value || csatSubmitting.value) return
  if (csatScore.value < 1) {
    uni.showToast({ title: '请先选择星级', icon: 'none' })
    return
  }
  csatSubmitting.value = true
  try {
    await submitWorkOrderCsat(workOrderId.value, {
      score: csatScore.value,
      comment: csatComment.value.trim() || undefined,
    })
    uni.showToast({ title: '感谢评价', icon: 'success' })
    csatDismissed.value = true
    await load()
  } catch (e) {
    toastError(e)
  } finally {
    csatSubmitting.value = false
  }
}
</script>

<template>
  <view v-if="!workOrderId" class="page">
    <view class="head row">
      <text class="title-lg">我的工单</text>
      <text class="muted">AI 转报与异常处理进度</text>
    </view>

    <state-view
      :loading="loading && !list.length"
      :error="error"
      :empty="!loading && !list.length"
      empty-text="还没有工单。向 AI 客服反馈问题后，转报的工单会出现在这里"
      @retry="load"
    >
      <view v-for="item in list" :key="item.id" class="card wo-card" @click="goDetail(item)">
        <view class="row">
          <status-tag :text="workOrderStatusText(item.status)" :tone="workOrderStatusTone(item.status)" />
          <text class="faint">{{ fmtTime(item.createTime) }}</text>
        </view>
        <view class="wo-title">{{ item.title ?? '工单' }}</view>
        <view class="row wo-meta">
          <text class="chip chip-info">{{ workOrderCategoryText(item.category) }}</text>
          <text class="chip chip-warning">{{ workOrderPriorityText(item.priority) }}</text>
          <text v-if="item.slotNo" class="faint">{{ item.cabinetNo ?? '—' }} · {{ item.slotNo }} 号格</text>
        </view>
        <view class="row bottom">
          <text class="faint">工单 #{{ item.id }}</text>
          <text class="link">查看详情 ›</text>
        </view>
      </view>
    </state-view>
  </view>

  <view v-else class="page">
    <state-view
      :loading="loading && !workOrder"
      :error="error"
      :empty="!loading && !workOrder"
      empty-text="工单不存在或不属于当前账号"
      @retry="load"
    >
      <view v-if="workOrder" class="card">
        <view class="row">
          <status-tag
            :text="workOrderStatusText(workOrder.status)"
            :tone="workOrderStatusTone(workOrder.status)"
          />
          <text class="chip chip-info">{{ workOrderCategoryText(workOrder.category) }}</text>
          <text class="chip chip-warning">{{ workOrderPriorityText(workOrder.priority) }}</text>
        </view>

        <view class="wo-title detail">{{ workOrder.title ?? '工单' }}</view>

        <view class="kv">
          <text class="k">工单号</text>
          <text class="v">#{{ workOrder.id }}</text>
        </view>
        <view v-if="workOrder.orderNo" class="kv" @click="goOrder">
          <text class="k">关联订单</text>
          <text class="v link">{{ workOrder.orderNo }} ›</text>
        </view>
        <view v-if="workOrder.slotNo" class="kv">
          <text class="k">位置</text>
          <text class="v">
            {{ workOrder.siteAddress ?? '—' }} {{ workOrder.cabinetNo ?? '—' }} · {{ workOrder.slotNo }} 号格
          </text>
        </view>
        <view class="kv">
          <text class="k">提交时间</text>
          <text class="v">{{ fmtTime(workOrder.createTime) }}</text>
        </view>
        <view v-if="workOrder.resolveDueAt && open" class="kv">
          <text class="k">处理时限</text>
          <text class="v">{{ fmtTime(workOrder.resolveDueAt) }}</text>
        </view>
        <view v-if="workOrder.closedAt" class="kv">
          <text class="k">关闭时间</text>
          <text class="v">{{ fmtTime(workOrder.closedAt) }}</text>
        </view>
        <view v-if="workOrder.closeReason" class="kv">
          <text class="k">关闭依据</text>
          <text class="v">{{ closeReasonText(workOrder.closeReason) }}</text>
        </view>
        <view v-if="workOrder.resultNote" class="result-note">
          <text class="k">处理结论</text>
          <text class="v">{{ workOrder.resultNote }}</text>
        </view>
        <view v-if="workOrder.reopenedCount" class="faint reopened">
          这张工单你已反馈过 {{ workOrder.reopenedCount }} 次“还没解决”
        </view>
        <view v-if="workOrder.csatScore" class="faint reopened">
          感谢你的评价：{{ workOrder.csatScore }} 星
        </view>
        <view v-if="canRate && !csatDismissed" class="csat">
          <text class="csat-title">这次处理得怎么样？</text>
          <view class="stars">
            <text
              v-for="n in 5"
              :key="n"
              class="star"
              :class="{ active: n <= csatScore }"
              @click="csatScore = n"
            >
              ★
            </text>
          </view>
          <textarea
            v-model="csatComment"
            class="textarea note"
            maxlength="300"
            placeholder="补充一句（可选）"
            placeholder-class="input-placeholder"
          />
          <view class="csat-actions">
            <button class="btn btn-primary" :loading="csatSubmitting" @click="onSubmitCsat">
              提交评价
            </button>
            <text class="faint link" @click="csatDismissed = true">暂不评价</text>
          </view>
        </view>
        <view v-if="mergedInto" class="merged" @click="goMerged">
          这张单已并入工单 #{{ mergedInto }}，点此查看 ›
        </view>

        <view v-if="open || reopenable" class="actions">
          <textarea
            v-model="note"
            class="textarea note"
            maxlength="300"
            placeholder="补充说明（可选）：现在是什么情况？"
            placeholder-class="input-placeholder"
          />
          <button v-if="open" class="btn btn-primary" :loading="acting" @click="onSolved">
            问题已解决
          </button>
          <button v-if="reopenable" class="btn btn-primary" :loading="acting" @click="onReopen">
            还没解决
          </button>
          <text class="faint hint">
            {{ open ? '如果问题还在，工作人员会继续处理；也可以点“AI 客服”补充说明'
              : '点“还没解决”会重新打开工单，工作人员会继续跟进' }}
          </text>
        </view>

        <view class="footer-links">
          <text class="link" @click="goSupport">还有问题？联系 AI 客服 ›</text>
        </view>
      </view>
    </state-view>
  </view>
</template>

<style scoped>
.head {
  padding: 8rpx 8rpx 24rpx;
}

.wo-card {
  padding: 24rpx 26rpx;
}

.wo-title {
  margin-top: 16rpx;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1.5;
  color: #1f2733;
}

.wo-title.detail {
  font-size: 34rpx;
  margin-bottom: 20rpx;
}

.wo-meta {
  margin-top: 14rpx;
  gap: 12rpx;
  flex-wrap: wrap;
}

.bottom {
  margin-top: 16rpx;
}

.kv {
  display: flex;
  align-items: flex-start;
  padding: 12rpx 0;
  border-bottom: 1px solid #f0f2f5;
}

.kv .k {
  width: 150rpx;
  flex-shrink: 0;
  color: #98a2b3;
  font-size: 25rpx;
}

.kv .v {
  flex: 1;
  color: #1f2733;
  font-size: 26rpx;
  line-height: 1.6;
  word-break: break-all;
}

.result-note {
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  background: #f7f8fa;
  border-radius: 12rpx;
}

.result-note .k {
  display: block;
  color: #98a2b3;
  font-size: 24rpx;
}

.result-note .v {
  display: block;
  margin-top: 8rpx;
  color: #1f2733;
  font-size: 26rpx;
  line-height: 1.7;
  white-space: pre-wrap;
}

.reopened {
  display: block;
  margin-top: 14rpx;
}

.csat {
  margin-top: 24rpx;
  padding: 20rpx 22rpx;
  background: #f7f8fa;
  border-radius: 12rpx;
}

.csat-title {
  display: block;
  color: #1f2733;
  font-size: 26rpx;
}

.stars {
  margin-top: 12rpx;
  display: flex;
  flex-direction: row;
}

.star {
  margin-right: 14rpx;
  color: #d0d5dd;
  font-size: 44rpx;
  line-height: 1;
}

.star.active {
  color: #f5a623;
}

.csat-actions {
  margin-top: 8rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.merged {
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  background: #eef4ff;
  border-radius: 12rpx;
  color: #2b6de5;
  font-size: 25rpx;
}

.actions {
  margin-top: 28rpx;
  padding-top: 24rpx;
  border-top: 1px solid #f0f2f5;
}

.note {
  width: 100%;
  min-height: 120rpx;
  margin-bottom: 20rpx;
  padding: 18rpx 22rpx;
  box-sizing: border-box;
  background: #f7f8fa;
  border: 1px solid #e6e9ee;
  border-radius: 12rpx;
  font-size: 26rpx;
  color: #1f2733;
}

.hint {
  display: block;
  margin-top: 16rpx;
  line-height: 1.6;
}

.footer-links {
  margin-top: 24rpx;
  text-align: center;
}

.link {
  color: #2b6de5;
  font-size: 25rpx;
}
</style>

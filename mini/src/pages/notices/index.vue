<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { fetchNotices, readNotice } from '@/api'
import type { NoticeView } from '@/api/schema-mini'
import { isLoggedIn } from '@/stores/auth'
import { noticeChannelText, noticeKindText } from '@/utils/dict'
import { fmtTime, toastError } from '@/utils/format'

const notices = ref<NoticeView[]>([])
const loading = ref(false)
const error = ref('')

const unreadCount = computed(() => notices.value.filter((item) => !item.doneAt).length)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    notices.value = await fetchNotices()
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

async function onRead(notice: NoticeView): Promise<void> {
  if (notice.doneAt || !notice.id) return
  try {
    await readNotice(String(notice.id))
    notice.doneAt = new Date().toISOString()
  } catch (e) {
    toastError(e)
  }
}

function goOrder(notice: NoticeView): void {
  if (notice.orderNo) {
    uni.navigateTo({ url: `/pages/order-detail/index?orderNo=${notice.orderNo}` })
  }
}

/** 工单通知优先落工单详情：用户能直接看到处理结论并确认/反馈没解决。 */
function goWorkOrder(notice: NoticeView): void {
  if (notice.workOrderId) {
    uni.navigateTo({ url: `/pages/work-order/index?id=${notice.workOrderId}` })
  }
}
</script>

<template>
  <view class="page">
    <view class="head row">
      <text class="title-lg">消息</text>
      <text class="muted">{{ unreadCount ? `${unreadCount} 条未读` : '全部已读' }}</text>
    </view>

    <state-view
      :loading="loading && !notices.length"
      :error="error"
      :empty="!loading && !notices.length"
      empty-text="还没有消息。门未关提醒、遗留物处理等通知会出现在这里"
      @retry="load"
    >
      <view
        v-for="notice in notices"
        :key="notice.id"
        class="card notice-card"
        :class="{ unread: !notice.doneAt }"
        @click="onRead(notice)"
      >
        <view class="row">
          <view class="row-start gap-8">
            <text class="chip chip-primary">{{ noticeKindText(notice.kind) }}</text>
            <text class="chip chip-info">{{ noticeChannelText(notice.channel) }}</text>
          </view>
          <text class="faint">{{ fmtTime(notice.createTime) }}</text>
        </view>

        <text class="content">{{ notice.content }}</text>

        <view class="row bottom">
          <text v-if="notice.workOrderId" class="link" @click.stop="goWorkOrder(notice)">
            查看工单 #{{ notice.workOrderId }} ›
          </text>
          <text v-else-if="notice.orderNo" class="link" @click.stop="goOrder(notice)">
            关联订单 {{ notice.orderNo }} ›
          </text>
          <text v-else class="faint" />
          <text class="faint">{{ notice.doneAt ? '已读' : '点击标记已读' }}</text>
        </view>

        <view v-if="notice.doneNote" class="done-note">处理结果：{{ notice.doneNote }}</view>
      </view>
    </state-view>
  </view>
</template>

<style scoped>
.head {
  padding: 8rpx 8rpx 24rpx;
}

.notice-card {
  padding: 24rpx 26rpx;
}

.notice-card.unread {
  border-left: 6rpx solid #2b6de5;
}

.content {
  display: block;
  margin-top: 16rpx;
  font-size: 28rpx;
  line-height: 1.7;
  color: #1f2733;
}

.bottom {
  margin-top: 16rpx;
}

.link {
  color: #2b6de5;
  font-size: 24rpx;
}

.done-note {
  margin-top: 14rpx;
  padding: 12rpx 16rpx;
  background: #f7f8fa;
  border-radius: 10rpx;
  color: #6b7684;
  font-size: 24rpx;
  line-height: 1.6;
}
</style>

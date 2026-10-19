<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { fetchMyOrders, fetchSites } from '@/api'
import type { OrderView, SiteView } from '@/api/schema-mini'
import { isLoggedIn } from '@/stores/auth'
import { isOrderActive, orderStatusHint, orderStatusText, orderStatusTone } from '@/utils/dict'
import { toastError } from '@/utils/format'

const sites = ref<SiteView[]>([])
const activeOrder = ref<OrderView | null>(null)
const loading = ref(false)
const error = ref('')
const keyword = ref('')

const filteredSites = computed(() => {
  const key = keyword.value.trim()
  if (!key) return sites.value
  return sites.value.filter((site) =>
    [site.name, site.address, site.category].some((value) => String(value ?? '').includes(key)),
  )
})

// 压测档种子可以到 500 个点位：一次把 500 张卡片塞进小程序会直接把它拖死，
// 所以先渲染 30 个，触底再加。数据本身仍是一次拉回，不改变后端分页能力（后端没有点位分页接口）。
const visibleCount = ref(30)
const visibleSites = computed(() => filteredSites.value.slice(0, visibleCount.value))
const hasMore = computed(() => filteredSites.value.length > visibleCount.value)

watch(keyword, () => {
  visibleCount.value = 30
})

function loadMore(): void {
  if (hasMore.value) visibleCount.value += 30
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [siteList, orders] = await Promise.all([fetchSites(), fetchMyOrders(10)])
    sites.value = siteList ?? []
    activeOrder.value = (orders ?? []).find((order) => isOrderActive(order.status)) ?? null
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

onReachBottom(loadMore)

function goSite(site: SiteView): void {
  uni.navigateTo({ url: `/pages/site/index?siteCode=${site.siteCode}` })
}

function goDetail(order: OrderView): void {
  uni.navigateTo({ url: `/pages/order-detail/index?orderNo=${order.orderNo}` })
}

function goPickup(): void {
  uni.navigateTo({ url: '/pages/pickup/index' })
}

function goSupport(): void {
  uni.navigateTo({ url: '/pages/support/index' })
}
</script>

<template>
  <view class="page home">
    <view class="hero">
      <view class="hero-title">智慧寄存柜</view>
      <view class="muted">先看余位，再走过去；存件即开柜</view>
      <input
        v-model="keyword"
        class="input search"
        placeholder="搜点位名称或地址"
        placeholder-class="input-placeholder"
      />
    </view>

    <view v-if="activeOrder" class="card active-card" @click="goDetail(activeOrder)">
      <view class="row">
        <status-tag
          :text="orderStatusText(activeOrder.status)"
          :tone="orderStatusTone(activeOrder.status)"
        />
        <text class="muted">{{ activeOrder.cabinetNo ?? '—' }} · {{ activeOrder.slotNo ?? '—' }} 号格</text>
      </view>
      <view class="active-title">你有一张进行中的寄存单</view>
      <view class="muted">{{ orderStatusHint(activeOrder.status) }}</view>
      <view class="active-action">查看并处理 ›</view>
    </view>

    <view class="quick-row">
      <view class="quick" @click="goPickup">
        <text class="quick-icon">码</text>
        <text class="quick-text">凭码取件</text>
        <text class="faint">手机没电也能取</text>
      </view>
      <view class="quick" @click="goSupport">
        <text class="quick-icon ask">问</text>
        <text class="quick-text">AI 客服</text>
        <text class="faint">账单、取件问题</text>
      </view>
    </view>

    <view class="section-title">附近点位</view>

    <state-view
      :loading="loading && !sites.length"
      :error="error"
      :empty="!loading && !filteredSites.length"
      :empty-text="keyword ? '没有匹配的点位' : '暂无点位数据'"
      @retry="load"
    >
      <view
        v-for="site in visibleSites"
        :key="site.siteCode"
        class="card site-card"
        @click="goSite(site)"
      >
        <view class="row">
          <text class="site-name grow">{{ site.name }}</text>
          <text class="chip chip-info">{{ site.category ?? '—' }}</text>
        </view>
        <view class="muted address">{{ site.address }}</view>
        <view class="free-row">
          <text class="chip chip-primary">小格 {{ site.freeSmall ?? 0 }}</text>
          <text class="chip chip-success">中格 {{ site.freeMedium ?? 0 }}</text>
          <text class="chip chip-warning">大格 {{ site.freeLarge ?? 0 }}</text>
          <text class="faint cabinet-count">{{ site.cabinetCount ?? 0 }} 台柜机</text>
        </view>
      </view>

      <view v-if="hasMore" class="load-more" @click="loadMore">
        还有 {{ filteredSites.length - visibleCount }} 个点位，点此加载更多
      </view>
      <view v-else-if="filteredSites.length > 30" class="load-more faint">
        已显示全部 {{ filteredSites.length }} 个点位
      </view>
    </state-view>
  </view>
</template>

<style scoped>
.home {
  padding-top: 32rpx;
}

.hero {
  padding: 0 8rpx 12rpx;
}

.hero-title {
  font-size: 44rpx;
  font-weight: 700;
  color: #1f2733;
}

.hero .muted {
  margin-top: 8rpx;
}

.search {
  margin-top: 24rpx;
  background: #ffffff;
}

.active-card {
  margin-top: 20rpx;
  border: 2rpx solid #dbe6ff;
  background: linear-gradient(180deg, #f7faff, #ffffff);
}

.active-title {
  margin-top: 18rpx;
  font-size: 32rpx;
  font-weight: 600;
}

.active-card .muted {
  margin-top: 8rpx;
}

.active-action {
  margin-top: 18rpx;
  color: #2b6de5;
  font-size: 26rpx;
  font-weight: 500;
}

.quick-row {
  display: flex;
  gap: 20rpx;
  margin-top: 4rpx;
}

.quick {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 26rpx 24rpx;
  background: #ffffff;
  border-radius: 20rpx;
  box-shadow: 0 4rpx 16rpx rgba(31, 39, 51, 0.04);
}

.quick-icon {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  background: #eaf1ff;
  color: #2b6de5;
  font-size: 30rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.quick-icon.ask {
  background: #ecf6e8;
  color: #3d8b2f;
}

.quick-text {
  margin-top: 16rpx;
  font-size: 28rpx;
  font-weight: 600;
}

.quick .faint {
  margin-top: 6rpx;
}

.site-card {
  padding: 26rpx 28rpx;
}

.site-name {
  font-size: 31rpx;
  font-weight: 600;
  margin-right: 16rpx;
}

.address {
  margin-top: 8rpx;
}

.free-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 18rpx;
  flex-wrap: wrap;
}

.cabinet-count {
  margin-left: auto;
}

.load-more {
  padding: 24rpx 0 40rpx;
  text-align: center;
  color: #2b6de5;
  font-size: 24rpx;
}
</style>

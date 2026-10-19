<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import { closedByHelper, fetchAvailability, fetchClosableSlots, fetchSiteCabinets } from '@/api'
import type { CabinetView, ClosableView } from '@/api/schema-mini'
import {
  cabinetAcceptingNewOrders,
  cabinetStatusText,
  onlineStateText,
} from '@/utils/dict'
import { fmtSize, toastError } from '@/utils/format'

interface CabinetRow {
  cabinet: CabinetView
  /** sizeType → 剩余可用格口（字符串，原样展示） */
  free: Record<string, string>
  freeLoaded: boolean
  closables: ClosableView[]
  closablesLoaded: boolean
  closablesLoading: boolean
}

const siteCode = ref('')
const rows = ref<CabinetRow[]>([])
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  if (!siteCode.value) return
  loading.value = true
  error.value = ''
  try {
    const cabinets = await fetchSiteCabinets(siteCode.value)
    rows.value = (cabinets ?? [])
      .filter((cabinet) => Boolean(cabinet.cabinetNo))
      .map((cabinet) => ({
        cabinet,
        free: {},
        freeLoaded: false,
        closables: [],
        closablesLoaded: false,
        closablesLoading: false,
      }))
    // 每台柜机各问一次余位：点位下通常只有一两台，并发拉完再一起渲染
    await Promise.all(rows.value.map((row) => loadFree(row)))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    toastError(e)
  } finally {
    loading.value = false
  }
}

async function loadFree(row: CabinetRow): Promise<void> {
  try {
    const view = await fetchAvailability(String(row.cabinet.cabinetNo))
    const free: Record<string, string> = {}
    for (const item of view.sizes ?? []) {
      if (item.sizeType) free[item.sizeType] = String(item.free ?? '0')
    }
    row.free = free
  } catch {
    // 单台余位失败不拖垮整个点位页：卡片上显示“余位未知”，下单页会重新查
  } finally {
    row.freeLoaded = true
  }
}

function totalFree(row: CabinetRow): number {
  return Object.values(row.free).reduce((sum, value) => sum + Number(value || 0), 0)
}

function canOrder(row: CabinetRow): boolean {
  return cabinetAcceptingNewOrders(row.cabinet.status) && (!row.freeLoaded || totalFree(row) > 0)
}

function goCreate(row: CabinetRow): void {
  if (!cabinetAcceptingNewOrders(row.cabinet.status)) {
    uni.showToast({ title: `柜机当前状态：${cabinetStatusText(row.cabinet.status)}`, icon: 'none' })
    return
  }
  if (row.freeLoaded && totalFree(row) === 0) {
    uni.showToast({ title: '这台柜机暂时没有可用格口', icon: 'none' })
    return
  }
  uni.navigateTo({
    url: `/pages/order-create/index?cabinetNo=${row.cabinet.cabinetNo}&siteCode=${siteCode.value}`,
  })
}

async function toggleClosables(row: CabinetRow): Promise<void> {
  if (row.closablesLoaded) {
    row.closablesLoaded = false
    return
  }
  row.closablesLoading = true
  try {
    row.closables = await fetchClosableSlots(String(row.cabinet.cabinetNo))
    row.closablesLoaded = true
    if (!row.closables.length) {
      uni.showToast({ title: '这台柜机没有待关门的格口', icon: 'none' })
    }
  } catch (e) {
    toastError(e)
  } finally {
    row.closablesLoading = false
  }
}

function closeForHelper(row: CabinetRow, slot: ClosableView): void {
  uni.showModal({
    title: '帮忙关门',
    content: `确认已将 ${row.cabinet.cabinetNo} 的 ${slot.slotNo} 号格门关好？`,
    success: async (res) => {
      if (!res.confirm) return
      try {
        await closedByHelper(String(row.cabinet.cabinetNo), String(slot.slotNo))
        uni.showToast({ title: '已记录，感谢帮忙', icon: 'success' })
        row.closables = row.closables.filter((item) => item.slotNo !== slot.slotNo)
        await loadFree(row)
      } catch (e) {
        toastError(e)
      }
    },
  })
}

onLoad((query) => {
  siteCode.value = String(query?.siteCode ?? '')
  load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <view class="head">
      <text class="title-lg">点位 {{ siteCode }}</text>
      <text class="muted">选一台柜机存件；路过看到柜门没关，也可以顺手帮忙</text>
    </view>

    <state-view
      :loading="loading && !rows.length"
      :error="error"
      :empty="!loading && !rows.length"
      empty-text="该点位暂无柜机"
      @retry="load"
    >
      <view v-for="row in rows" :key="row.cabinet.cabinetNo" class="card">
        <view class="row">
          <text class="cabinet-name grow">{{ row.cabinet.name ?? row.cabinet.cabinetNo }}</text>
          <text
            class="chip"
            :class="cabinetAcceptingNewOrders(row.cabinet.status) ? 'chip-success' : 'chip-danger'"
          >
            {{ cabinetStatusText(row.cabinet.status) }}
          </text>
        </view>

        <view class="row meta">
          <text class="muted">编号 {{ row.cabinet.cabinetNo }}</text>
          <text class="faint">{{ onlineStateText(row.cabinet.onlineState) }}</text>
        </view>

        <view class="free-row">
          <template v-if="row.freeLoaded">
            <text class="chip chip-primary">小格 {{ row.free.SMALL ?? 0 }}</text>
            <text class="chip chip-success">中格 {{ row.free.MEDIUM ?? 0 }}</text>
            <text class="chip chip-warning">大格 {{ row.free.LARGE ?? 0 }}</text>
          </template>
          <text v-else class="faint">余位查询中…</text>
        </view>

        <button class="btn btn-primary btn-block order-btn" :disabled="!canOrder(row)" @click="goCreate(row)">
          {{ canOrder(row) ? '在这台柜机存件' : '暂无可用户格口' }}
        </button>

        <view class="helper-toggle" @click="toggleClosables(row)">
          <text>{{ row.closablesLoaded ? '收起待关门格口' : '看到柜门没关？查看待关门格口' }}</text>
          <text class="helper-arrow">{{ row.closablesLoaded ? '▲' : '▼' }}</text>
        </view>

        <view v-if="row.closablesLoading" class="faint helper-loading">查询中…</view>
        <view v-else-if="row.closablesLoaded" class="helper-list">
          <view v-if="!row.closables.length" class="faint">没有待关门的格口</view>
          <view v-for="slot in row.closables" :key="slot.slotNo" class="helper-item">
            <text class="text">{{ slot.slotNo }} 号格 · {{ fmtSize(slot.sizeType) }}</text>
            <button class="btn btn-ghost btn-sm" @click="closeForHelper(row, slot)">我帮他关上了</button>
          </view>
        </view>
      </view>
    </state-view>
  </view>
</template>

<style scoped>
.head {
  padding: 8rpx 8rpx 24rpx;
}

.head .muted {
  display: block;
  margin-top: 8rpx;
}

.cabinet-name {
  font-size: 32rpx;
  font-weight: 600;
  margin-right: 16rpx;
}

.meta {
  margin-top: 10rpx;
}

.free-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 20rpx;
  flex-wrap: wrap;
}

.order-btn {
  margin-top: 24rpx;
}

.helper-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1px solid #eef1f5;
  color: #6b7684;
  font-size: 24rpx;
}

.helper-arrow {
  font-size: 20rpx;
  color: #98a2b3;
}

.helper-loading {
  margin-top: 14rpx;
}

.helper-list {
  margin-top: 8rpx;
}

.helper-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
  border-bottom: 1px solid #f5f6f8;
}

.helper-item:last-child {
  border-bottom: none;
}
</style>

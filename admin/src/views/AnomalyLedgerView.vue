<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'

import {
  ANOMALY_LABEL,
  ANOMALY_OPTIONS,
  compartmentDetail,
  forceOpen,
  listAnomalies,
  rebuildFreeSet,
  resolveAnomaly,
  summarizeAnomalies,
  waiveDisputeFee,
  type CompartmentDetail,
  type LedgerView,
  type SummaryView,
} from '@/api/locker'
import type { PageResult } from '@/types/api'
import { useAuthStore } from '@/stores/auth'

/**
 * 异常格口台账（第 14E 刀）。
 *
 * 页面上三个刻意的做法：
 * 1. **按钮按权限点显示，但真正的拒绝在后端**：这里只是少点一次 403，不充当门禁；
 * 2. **处置动作都必须填说明/事由**：后端也是这么要求的，前端提前挡住，
 *    免得用户填完半屏才被服务端退回；
 * 3. **停留时长用"卡了几小时"而不是精确时间**：运维排片关心的是积压，不是时间戳。
 */
const auth = useAuthStore()

const loading = ref(false)
const page = ref<PageResult<LedgerView>>({ pageNum: 1, pageSize: 20, total: 0, pages: 0, records: [] })
const summary = ref<SummaryView[]>([])

const query = reactive({
  anomaly: undefined as string | undefined,
  siteId: undefined as number | undefined,
  pageNum: 1,
  pageSize: 20,
  orderBy: 'anomalyAt' as string | undefined,
  asc: true,
})

const detail = ref<CompartmentDetail | null>(null)
const detailVisible = ref(false)
const detailLoading = ref(false)

const totalStuck = computed(() => summary.value.reduce((sum, item) => sum + item.rowsCount, 0))

/**
 * Element Plus 的表格插槽拿到的是 `DefaultRow`（它不知道你的 :data 是什么类型），
 * 所以行参数统一收 `unknown` 再在这里转一次。比在每个列里写 `as` 好：
 * 转换只发生在一处，将来改数据结构时不会遗漏。比把类型写宽（any）也好：不会把错字段传下去。
 */
function asRow(raw: unknown): LedgerView {
  return raw as LedgerView
}

function label(anomaly?: string): string {
  return (anomaly && ANOMALY_LABEL[anomaly]) || anomaly || '-'
}

/** 卡了多久：分钟换算成"x 小时 y 分"，超过一天直接给天数 */
function stuck(raw: unknown): string {
  const minutes = Number(asRow(raw).stuckMinutes ?? 0)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

function dangerLevel(raw: unknown): 'danger' | 'warning' | 'info' {
  const minutes = Number(asRow(raw).stuckMinutes ?? 0)
  if (minutes >= 24 * 60) return 'danger'
  if (minutes >= 60) return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const [list, sums] = await Promise.all([
      listAnomalies({
        anomaly: query.anomaly,
        siteId: query.siteId,
        pageNum: query.pageNum,
        pageSize: query.pageSize,
        orderBy: query.orderBy,
        asc: query.asc,
      }),
      summarizeAnomalies(query.siteId),
    ])
    page.value = list
    summary.value = sums
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.pageNum = 1
  return load()
}

function onSizeChange() {
  query.pageNum = 1
  return load()
}

function onSortChange(payload: { prop: string | null; order: 'ascending' | 'descending' | null }) {
  query.pageNum = 1
  if (!payload.order || !payload.prop) {
    query.orderBy = 'anomalyAt'
    query.asc = true
    return load()
  }
  query.orderBy = payload.prop
  query.asc = payload.order === 'ascending'
  return load()
}

async function openDetail(raw: unknown) {
  const row = asRow(raw)
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await compartmentDetail(row.compartmentId)
  } finally {
    detailLoading.value = false
  }
}

/** 清柜：只有后端确认门已关才会成功，所以这里不预先判断门状态，交给服务端说原因。 */
async function onResolve(raw: unknown) {
  const row = asRow(raw)
  const note = await promptText('清柜确认', `请写下现场处理结果（柜机 ${row.cabinetNo} / ${row.slotNo}）`)
  if (!note) return
  await resolveAnomaly(row.compartmentId, note)
  ElMessage.success('已解除异常，格口恢复可分配')
  return load()
}

async function onForceOpen(raw: unknown) {
  const row = asRow(raw)
  const reason = await promptText('强制开柜', '请输入事由（会进审计）。格口内可能留有他人物品。')
  if (!reason) return
  try {
    await ElMessageBox.confirm(
      `确认强制打开 ${row.cabinetNo} / ${row.slotNo}？该操作会被完整审计。`,
      '二次确认',
      { type: 'warning', confirmButtonText: '确认开柜', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await forceOpen(row.compartmentId, reason)
  ElMessage.success('开柜指令已下发')
  return load()
}

/** 免除只对“当前详情里那张单”做，所以不取行参数：避免列表行与抽屉里看的不是同一张单。 */
async function onWaive() {
  const orderNo = detail.value?.currentOrder?.orderNo
  if (!orderNo) {
    ElMessage.warning('该格口当前没有活动单，无从免除')
    return
  }
  try {
    await waiveDisputeFee(orderNo)
    ElMessage.success('已按设备误报免除争议期间费用并结束该单')
  } finally {
    await load()
    detailVisible.value = false
  }
}

async function onRebuild(raw: unknown) {
  const row = asRow(raw)
  await rebuildFreeSet(row.cabinetId)
  ElMessage.success(`已按 DB 真相同步 ${row.cabinetNo} 的空闲集合`)
  return load()
}

async function promptText(title: string, message: string): Promise<string | null> {
  try {
    const result = await ElMessageBox.prompt(message, title, {
      inputValidator: (value: string) => (value && value.trim().length >= 4 ? true : '至少写 4 个字，事后要能看懂'),
    })
    return (result.value ?? '').trim()
  } catch {
    return null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="chips">
      <ElTag type="info" effect="plain">积压合计 {{ totalStuck }}</ElTag>
      <ElTag v-for="item in summary" :key="item.anomaly" :type="item.rowsCount > 0 ? 'warning' : 'info'" effect="plain">
        {{ label(item.anomaly) }} {{ item.rowsCount }}（最久 {{ Math.round(item.longestMinutes / 60) }} 小时）
      </ElTag>
    </div>

    <ElForm inline @submit.prevent>
      <ElFormItem label="异常类型">
        <ElSelect v-model="query.anomaly" clearable placeholder="全部" style="width: 150px">
          <ElOption v-for="item in ANOMALY_OPTIONS" :key="item.value" :value="item.value" :label="item.label" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="点位 ID">
        <ElInput v-model.number="query.siteId" placeholder="不填为全部" clearable style="width: 130px" />
      </ElFormItem>
      <ElFormItem>
        <ElButton type="primary" :loading="loading" @click="onSearch">查询</ElButton>
      </ElFormItem>
    </ElForm>

    <ElTable
      v-loading="loading"
      :data="page.records"
      border
      :default-sort="{ prop: 'anomalyAt', order: 'ascending' }"
      @sort-change="onSortChange"
    >
      <ElTableColumn prop="cabinetNo" label="柜机" width="140" />
      <ElTableColumn prop="slotNo" label="格口" width="90" />
      <ElTableColumn prop="siteName" label="点位" min-width="120" />
      <ElTableColumn prop="anomaly" label="异常" width="120">
        <template #default="{ row }">
          <ElTag :type="dangerLevel(row)" size="small">{{ label(row.anomaly) }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="stuckMinutes" label="卡住时长" width="130" sortable="custom">
        <template #default="{ row }">{{ stuck(row) }}</template>
      </ElTableColumn>
      <ElTableColumn prop="anomalyReason" label="现场说明" min-width="220" show-overflow-tooltip />
      <ElTableColumn label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
          <ElButton v-if="auth.hasAuthority('locker:compartment:resolve')" link type="primary" @click="onResolve(row)">
            清柜
          </ElButton>
          <ElButton v-if="auth.hasAuthority('locker:door:force-open')" link type="warning" @click="onForceOpen(row)">
            强制开柜
          </ElButton>
          <ElButton v-if="auth.hasAuthority('locker:freeset:rebuild')" link @click="onRebuild(row)">重建空闲集合</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <ElPagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="page.total"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="load"
      @size-change="onSizeChange"
    />

    <ElDrawer v-model="detailVisible" size="46%" title="格口现场详情">
      <div v-loading="detailLoading">
        <ElDescriptions v-if="detail" :column="1" border>
          <ElDescriptionsItem label="柜机 / 格口">
            {{ detail.compartment.cabinetId }} / {{ detail.compartment.slotNo }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="格口状态">{{ detail.compartment.status }}</ElDescriptionsItem>
          <ElDescriptionsItem label="异常">{{ label(detail.compartment.anomaly) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="门开着的时间点">{{ detail.compartment.doorOpenAt ?? '—（门已关）' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="最近关门">{{ detail.compartment.doorClosedAt ?? '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="柜内物检">
            {{ detail.compartment.presence ?? '未测' }}
            <span class="mono">{{ detail.compartment.presenceCheckedAt ?? '' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="当前活动单">
            <template v-if="detail.currentOrder">
              {{ detail.currentOrder.orderNo }}（{{ detail.currentOrder.status }}）
              <ElButton
                v-if="auth.hasAuthority('locker:order:waive-fee')"
                link
                type="warning"
                class="waive"
                @click="onWaive()"
              >
                按设备误报免除并结束
              </ElButton>
            </template>
            <span v-else>—</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <h4>观测时间线（只增不改，判误报靠这里）</h4>
        <ElTable :data="detail?.evidence ?? []" size="small" border>
          <ElTableColumn prop="createTime" label="时刻" width="170" />
          <ElTableColumn prop="source" label="来源" width="110" />
          <ElTableColumn prop="presence" label="结论" width="90" />
          <ElTableColumn prop="note" label="依据" min-width="180" show-overflow-tooltip />
        </ElTable>
        <p class="tip">
          免除金额由后端按「计费起点 → 争议起点」算出，页面上没有也无法填金额；证据不互相打脸时服务端会直接拒绝。
        </p>
      </div>
    </ElDrawer>
  </div>
</template>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
}

.tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}

.waive {
  margin-left: 12px;
}

.mono {
  font-family: Consolas, monospace;
  color: #909399;
  margin-left: 6px;
}
</style>

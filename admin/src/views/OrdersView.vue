<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { fetchOrderDetail, fetchOrders } from '@/api'
import type { OrderDetail, OrderRow } from '@/api/contracts'
import { ApiError } from '@/api/http'
import {
  commandActionText,
  commandStateText,
  disputeStateText,
  evidenceMomentText,
  evidenceSourceText,
  orderStatusTag,
  orderStatusText,
  orderCloseReasonText,
  pointTxnTypeText,
  presenceText,
  sizeTypeText,
  workOrderCategoryText,
  workOrderPriorityText,
  workOrderStatusTag,
  workOrderStatusText,
} from '@/utils/dict'
import { fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const loading = ref(false)
const rows = ref<OrderRow[]>([])
const total = ref(0)
const pageQuery = reactive({ pageNum: 1, pageSize: 20 })

const statusOptions = [
  { value: 'INIT', label: '待分配格口' },
  { value: 'RESERVED', label: '待开柜' },
  { value: 'OPENING', label: '正在开门' },
  { value: 'STORED', label: '已存件' },
  { value: 'ACTIVE', label: '计费中' },
  { value: 'TEMP_OPEN', label: '临时开门' },
  { value: 'SETTLING', label: '结算中' },
  { value: 'CLOSED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'ABNORMAL', label: '异常' },
  { value: 'EXPIRED', label: '已逾期' },
]

/** 筛选条件不放进 pageQuery：翻页时不该把用户正在输入但没提交的值带上去 */
const filters = reactive({
  orderNo: '',
  phone: '',
  status: '',
  range: [] as string[],
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await fetchOrders({
      ...pageQuery,
      orderNo: filters.orderNo.trim() || undefined,
      phone: filters.phone.trim() || undefined,
      status: filters.status || undefined,
      from: filters.range[0] || undefined,
      to: filters.range[1] || undefined,
    })
    rows.value = page.records ?? []
    total.value = Number(page.total ?? 0)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function search(): void {
  pageQuery.pageNum = 1
  load()
}

function reset(): void {
  filters.orderNo = ''
  filters.phone = ''
  filters.status = ''
  filters.range = []
  pageQuery.pageNum = 1
  load()
}

function onPageChange(pageNum: number): void {
  pageQuery.pageNum = pageNum
  load()
}

// ── 详情抽屉：状态 + 钱 + 设备动作 + 证据 + 关联工单 ───────────────
const drawerOpen = ref(false)
const detail = ref<OrderDetail | null>(null)
const detailLoading = ref(false)
const currentNo = ref('')

async function openDetail(row: OrderRow): Promise<void> {
  if (!row.orderNo) return
  currentNo.value = row.orderNo
  detail.value = null
  drawerOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await fetchOrderDetail(row.orderNo)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function points(value?: number): string {
  return value === undefined || value === null ? '0' : String(value)
}

onMounted(() => {
  if (auth.hasAuthority('order:list')) load()
})
</script>

<template>
  <el-card>
    <div class="toolbar">
      <div class="filters">
        <el-input
          v-model="filters.orderNo"
          clearable
          placeholder="订单号（精确）"
          class="filter-input"
          @keyup.enter="search"
        />
        <el-input
          v-model="filters.phone"
          clearable
          placeholder="手机号（精确）"
          class="filter-input"
          @keyup.enter="search"
        />
        <el-select v-model="filters.status" clearable placeholder="全部状态" class="filter-select">
          <el-option
            v-for="option in statusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="创建起"
          end-placeholder="创建止"
          class="filter-range"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      :row-style="{ cursor: 'pointer' }"
      scrollbar-always-on
      @row-click="openDetail"
    >
      <el-table-column label="订单号" prop="orderNo" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="orderStatusTag(row.status)" size="small">
            {{ orderStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="尺寸" width="80">
        <template #default="{ row }">{{ sizeTypeText(row.sizeType) }}</template>
      </el-table-column>
      <el-table-column label="位置" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.cabinetNo ?? '—' }} / {{ row.slotNo ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column label="客户" prop="customerName" width="120" show-overflow-tooltip />
      <el-table-column label="预收" width="80" align="right">
        <template #default="{ row }">{{ points(row.prepayPoints) }}</template>
      </el-table-column>
      <el-table-column label="实收" width="80" align="right">
        <template #default="{ row }">{{ points(row.settledPoints) }}</template>
      </el-table-column>
      <el-table-column label="欠费" width="80" align="right">
        <template #default="{ row }">
          <span :class="{ arrears: (row.arrearsPoints ?? 0) > 0 }">{{ points(row.arrearsPoints) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="结束时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.finishedAt) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有符合条件的订单" :image-size="60" />
      </template>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageQuery.pageSize"
      :current-page="pageQuery.pageNum"
      @current-change="onPageChange"
    />
  </el-card>

  <el-drawer v-model="drawerOpen" size="760px" :title="`订单 ${currentNo}`">
    <div v-loading="detailLoading">
      <template v-if="detail">
        <h4 class="section-title">概要</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="orderStatusTag(detail.row?.status)" size="small">
              {{ orderStatusText(detail.row?.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="尺寸">
            {{ sizeTypeText(detail.row?.sizeType) }}
          </el-descriptions-item>
          <el-descriptions-item label="客户">
            {{ detail.row?.customerName ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="联系电话">
            {{ detail.customerPhone ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="点位">
            {{ detail.row?.siteName ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="位置">
            {{ detail.row?.cabinetNo ?? '—' }} / {{ detail.row?.slotNo ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ fmtTime(detail.row?.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="开始计费">
            {{ fmtTime(detail.row?.startedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ fmtTime(detail.row?.finishedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束原因">
            {{ orderCloseReasonText(detail.closeReason) }}
          </el-descriptions-item>
          <el-descriptions-item label="争议">
            {{ disputeStateText(detail.disputeState) }}
          </el-descriptions-item>
          <el-descriptions-item label="重开/临时开门">
            临时开门 {{ detail.tempOpenCount ?? 0 }} 次；取件码错
            {{ detail.voucherWrongCount ?? 0 }} 次{{ detail.voucherLocked ? '（已锁定）' : '' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">资金</h4>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="预收">{{ points(detail.row?.prepayPoints) }}</el-descriptions-item>
          <el-descriptions-item label="实收">{{ points(detail.row?.settledPoints) }}</el-descriptions-item>
          <el-descriptions-item label="欠费">{{ points(detail.row?.arrearsPoints) }}</el-descriptions-item>
          <el-descriptions-item label="押金">{{ points(detail.depositPoints) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">账单明细</h4>
        <template v-if="detail.bill">
          <el-descriptions :column="3" border size="small" class="bill-summary">
            <el-descriptions-item label="时长">
              {{ detail.bill.billedMinutes ?? 0 }} 分钟（计费 {{ detail.bill.billedHours ?? 0 }} 小时）
            </el-descriptions-item>
            <el-descriptions-item label="单价">
              {{ detail.bill.unitPointsPerHour ?? 0 }} 点/小时
            </el-descriptions-item>
            <el-descriptions-item label="免费窗口">
              {{ detail.bill.freeMinutes ?? 0 }} 分钟
            </el-descriptions-item>
            <el-descriptions-item label="应收">{{ detail.bill.chargePoints ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="预收">{{ detail.bill.prepayPoints ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="实收/欠费">
              {{ detail.bill.settledPoints ?? 0 }} / {{ detail.bill.arrearsPoints ?? 0 }}
            </el-descriptions-item>
          </el-descriptions>
          <el-table v-if="detail.bill.lines?.length" :data="detail.bill.lines" size="small">
            <el-table-column label="时间" width="155">
              <template #default="{ row }">{{ fmtTime(row.at) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">{{ pointTxnTypeText(row.type) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="90" align="right">
              <template #default="{ row }">{{ row.amount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="余额" width="90" align="right">
              <template #default="{ row }">{{ row.balanceAfter ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip />
          </el-table>
          <el-empty v-else :image-size="50" description="这张单没有资金流水" />
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="这张单没有定价快照（历史单），无法重算账单；已落库的预收/实收/欠费照常展示。"
        />

        <h4 class="section-title">设备动作</h4>
        <el-table v-if="detail.commands?.length" :data="detail.commands" size="small">
          <el-table-column label="时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.sentAt) }}</template>
          </el-table-column>
          <el-table-column label="动作" width="110">
            <template #default="{ row }">{{ commandActionText(row.action) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="110">
            <template #default="{ row }">{{ commandStateText(row.state) }}</template>
          </el-table-column>
          <el-table-column label="重试" prop="retryCount" width="70" align="right" />
          <el-table-column label="回执时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.ackedAt) }}</template>
          </el-table-column>
          <el-table-column label="失败原因" prop="lastError" min-width="140" show-overflow-tooltip />
        </el-table>
        <el-empty v-else :image-size="50" description="这张单没有设备动作记录" />

        <h4 class="section-title">格口证据</h4>
        <el-table v-if="detail.evidence?.length" :data="detail.evidence" size="small">
          <el-table-column label="时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.at) }}</template>
          </el-table-column>
          <el-table-column label="采集点" width="110">
            <template #default="{ row }">{{ evidenceMomentText(row.moment) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template #default="{ row }">{{ evidenceSourceText(row.source) }}</template>
          </el-table-column>
          <el-table-column label="结论" width="80">
            <template #default="{ row }">{{ presenceText(row.presence) }}</template>
          </el-table-column>
          <el-table-column label="AI 标签" prop="aiLabel" width="120" show-overflow-tooltip />
          <el-table-column label="说明" prop="note" min-width="140" show-overflow-tooltip />
        </el-table>
        <el-empty v-else :image-size="50" description="暂无证据记录" />

        <h4 class="section-title">关联工单</h4>
        <el-table v-if="detail.workOrders?.length" :data="detail.workOrders" size="small">
          <el-table-column label="工单号" prop="id" width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="workOrderStatusTag(row.status)" size="small">
                {{ workOrderStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="110">
            <template #default="{ row }">{{ workOrderCategoryText(row.category) }}</template>
          </el-table-column>
          <el-table-column label="优先级" width="90">
            <template #default="{ row }">{{ workOrderPriorityText(row.priority) }}</template>
          </el-table-column>
          <el-table-column label="标题" prop="title" min-width="160" show-overflow-tooltip />
          <el-table-column label="创建时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="50" description="这张单没有开过工单" />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 14px;
  gap: 12px;
  flex-wrap: wrap;
}

.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-input {
  width: 200px;
}

.filter-select {
  width: 150px;
}

.filter-range {
  width: 260px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.section-title {
  margin: 18px 0 10px;
}

.bill-summary {
  margin-bottom: 10px;
}

.arrears {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>

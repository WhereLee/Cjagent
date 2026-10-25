<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  fetchAnomalies,
  fetchAnomalySummary,
  fetchCompartmentDetail,
  forceOpen,
  rebuildFreeSet,
  resolveCompartment,
} from '@/api'
import type { Detail, LedgerView } from '@/api/schema-admin'
import type { SummaryView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import {
  anomalyTag,
  anomalyText,
  evidenceMomentText,
  evidenceSourceText,
  orderStatusText,
  presenceText,
  slotStatusText,
} from '@/utils/dict'
import { fmtMinutes, fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canResolve = computed(() => auth.hasAuthority('locker:compartment:resolve'))
const canForceOpen = computed(() => auth.hasAuthority('locker:door:force-open'))
const canRebuild = computed(() => auth.hasAuthority('locker:freeset:rebuild'))

const loading = ref(false)
const rows = ref<LedgerView[]>([])
const total = ref(0)
const summary = ref<SummaryView[]>([])
const pageQuery = reactive({ pageNum: 1, pageSize: 20 })
const anomalyFilter = ref('')

const anomalyOptions = [
  { value: 'DOOR_OPEN', label: '门未关' },
  { value: 'CONTENT_LEFT', label: '有遗留物' },
  { value: 'CONTENT_UNVERIFIED', label: '物品未核实' },
  { value: 'SENSOR_CONFLICT', label: '传感器矛盾' },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await fetchAnomalies({
      ...pageQuery,
      anomaly: anomalyFilter.value || undefined,
    })
    rows.value = page.records ?? []
    total.value = Number(page.total ?? 0)
    summary.value = await fetchAnomalySummary()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function applyFilter(anomaly?: string | null): void {
  anomalyFilter.value = anomaly ?? ''
  pageQuery.pageNum = 1
  load()
}

function onPageChange(pageNum: number): void {
  pageQuery.pageNum = pageNum
  load()
}

// ── 格口详情抽屉 ─────────────────────────────────────────────────
const drawerOpen = ref(false)
const detail = ref<Detail | null>(null)
const current = ref<LedgerView | null>(null)
const detailLoading = ref(false)

async function openDetail(row: LedgerView): Promise<void> {
  current.value = row
  drawerOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await fetchCompartmentDetail(String(row.compartmentId))
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

// ── 清柜：解除格口异常 ───────────────────────────────────────────
const resolveDialog = ref(false)
const resolveNote = ref('')

function openResolve(row: LedgerView): void {
  current.value = row
  resolveNote.value = ''
  resolveDialog.value = true
}

async function onResolve(): Promise<void> {
  if (!current.value) return
  if (!resolveNote.value.trim()) {
    ElMessage.warning('处置说明必填：它是“人来现场看过”的唯一凭据')
    return
  }
  try {
    await resolveCompartment(String(current.value.compartmentId), { note: resolveNote.value })
    ElMessage.success('已清柜')
    resolveDialog.value = false
    drawerOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '清柜失败')
  }
}

// ── 强制开柜：两次确认，第一次填原因，第二次确认开柜 ─────────────
async function onForceOpen(row: LedgerView): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '强制开柜是绕过用户意愿的动作，必须留下原因。下一步还会再确认一次。',
      `强制开柜 ${row.cabinetNo ?? ''} / ${row.slotNo ?? ''}`,
      {
        type: 'warning',
        confirmButtonText: '下一步',
        cancelButtonText: '取消',
        inputPlaceholder: '开柜原因（必填）',
        inputValidator: (value: string) => (value && value.trim() ? true : '原因必填'),
      },
    )
    reason = result.value
  } catch {
    return
  }

  try {
    await ElMessageBox.confirm(
      `即将打开 ${row.cabinetNo ?? ''} / ${row.slotNo ?? ''} 的柜门，动作会留痕并记入审计。确认执行？`,
      '二次确认',
      { type: 'warning', confirmButtonText: '确认开柜', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }

  try {
    await forceOpen(String(row.compartmentId), { confirmed: true, reason })
    ElMessage.success('已强制开柜')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '强制开柜失败')
  }
}

async function onRebuild(row: LedgerView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '会用数据库真相同步该柜机的空闲格口集合：期间会少卖，不会超卖。确认重建？',
      '重建空闲格口集合',
      { type: 'info' },
    )
  } catch {
    return
  }
  try {
    await rebuildFreeSet(String(row.cabinetId))
    ElMessage.success('已重建')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '重建失败')
  }
}

onMounted(() => {
  if (auth.hasAuthority('locker:ledger:list')) load()
})
</script>

<template>
  <el-card>
    <el-row v-if="summary.length" :gutter="12" class="summary-row">
      <el-col v-for="item in summary" :key="item.anomaly" :span="6">
        <el-card shadow="never" class="summary-card" @click="applyFilter(item.anomaly)">
          <div class="summary-label">{{ anomalyText(item.anomaly) }}</div>
          <div class="summary-value">{{ item.rowsCount ?? 0 }} 条积压</div>
          <div class="summary-sub">最长滞留 {{ fmtMinutes(item.longestMinutes) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <div class="toolbar">
      <div class="filters">
        <el-select
          v-model="anomalyFilter"
          clearable
          placeholder="全部异常类型"
          class="filter-select"
          @change="applyFilter(anomalyFilter)"
        >
          <el-option
            v-for="option in anomalyOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-tag type="info" size="small">共 {{ total }} 格</el-tag>
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
      <el-table-column label="格口号" prop="slotNo" width="90" />
      <el-table-column label="柜机" prop="cabinetNo" width="110" />
      <el-table-column label="点位" prop="siteName" min-width="140" show-overflow-tooltip />
      <el-table-column label="异常" width="120">
        <template #default="{ row }">
          <el-tag :type="anomalyTag(row.anomaly)" size="small">{{ anomalyText(row.anomaly) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原因" prop="anomalyReason" min-width="180" show-overflow-tooltip />
      <el-table-column label="滞留" width="110">
        <template #default="{ row }">{{ fmtMinutes(row.stuckMinutes) }}</template>
      </el-table-column>
      <el-table-column label="发生时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.anomalyAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canResolve" link type="primary" @click.stop="openResolve(row)">清柜</el-button>
          <el-button v-if="canForceOpen" link type="danger" @click.stop="onForceOpen(row)">
            强制开柜
          </el-button>
          <el-button v-if="canRebuild" link type="primary" @click.stop="onRebuild(row)">
            重建空闲集
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前筛选下没有异常格口" :image-size="60" />
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

  <el-drawer
    v-model="drawerOpen"
    size="600px"
    :title="`格口 ${current?.cabinetNo ?? ''} / ${current?.slotNo ?? ''}`"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <h4 class="section-title">格口状态</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="格口状态">
            {{ slotStatusText(detail.compartment?.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="异常">
            <el-tag :type="anomalyTag(detail.compartment?.anomaly)" size="small">
              {{ anomalyText(detail.compartment?.anomaly) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="物品判定">
            {{ presenceText(detail.compartment?.presence) }}
          </el-descriptions-item>
          <el-descriptions-item label="当前订单">
            {{ detail.compartment?.currentOrderId ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="开门时间">
            {{ fmtTime(detail.compartment?.doorOpenAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="关门时间">
            {{ fmtTime(detail.compartment?.doorClosedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">物品证据</h4>
        <el-table v-if="detail.evidence?.length" :data="detail.evidence" size="small">
          <el-table-column label="采集点" width="110">
            <template #default="{ row }">{{ evidenceMomentText(row.moment) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template #default="{ row }">{{ evidenceSourceText(row.source) }}</template>
          </el-table-column>
          <el-table-column label="结论" width="90">
            <template #default="{ row }">{{ presenceText(row.presence) }}</template>
          </el-table-column>
          <el-table-column label="AI 标签" prop="aiLabel" width="110" show-overflow-tooltip />
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.at) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="50" description="暂无证据记录" />

        <h4 class="section-title">当前订单</h4>
        <el-descriptions v-if="detail.currentOrder" :column="2" border size="small">
          <el-descriptions-item label="订单号">
            {{ detail.currentOrder.orderNo ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ orderStatusText(detail.currentOrder.status) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else :image-size="50" description="无关联订单" />
      </template>

      <div class="drawer-actions">
        <el-button v-if="canResolve && current" type="primary" @click="openResolve(current)">
          清柜
        </el-button>
        <el-button v-if="canForceOpen && current" type="danger" @click="onForceOpen(current)">
          强制开柜
        </el-button>
      </div>
    </div>
  </el-drawer>

  <el-dialog v-model="resolveDialog" title="清柜：解除格口异常" width="520px">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="清柜要求门已关。填写的说明会进审计与故障流水，请写清现场看到了什么。"
      class="resolve-alert"
    />
    <el-form label-width="96px">
      <el-form-item label="处置说明" required>
        <el-input
          v-model="resolveNote"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="例如：现场确认柜门已关、柜内为空"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="resolveDialog = false">取消</el-button>
      <el-button type="primary" @click="onResolve">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.summary-row {
  margin-bottom: 4px;
}

.summary-card {
  cursor: pointer;
}

.summary-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.summary-value {
  font-size: 20px;
  font-weight: 600;
  margin: 4px 0;
}

.summary-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0;
}

.filters {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-select {
  width: 190px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.section-title {
  margin: 18px 0 10px;
}

.drawer-actions {
  margin-top: 20px;
  display: flex;
  gap: 8px;
}

.resolve-alert {
  margin-bottom: 14px;
}
</style>

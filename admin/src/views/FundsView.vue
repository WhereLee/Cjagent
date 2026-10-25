<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { changeCustomerStatus, fetchArrears, fetchUnrefundedDeposits } from '@/api'
import type { ArrearsView, DepositView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { depositStatusTag, depositStatusText } from '@/utils/dict'
import { fmtTime, pointsToYuan } from '@/utils/format'
import { usePagedList } from '@/composables/usePagedList'
import PagerBar from '@/components/PagerBar.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const activeTab = ref<'arrears' | 'deposits'>(
  auth.hasAuthority('locker:arrears:list') ? 'arrears' : 'deposits',
)

const allowedArrears = computed(() => auth.hasAuthority('locker:arrears:list'))
const allowedDeposits = computed(() => auth.hasAuthority('locker:deposit:unrefunded-list'))

// 两个 tab 的数据类型不同（欠费客户 / 押金流水），所以各用一套分页；对外再转发成一个，
// 多出来的只是两个 computed，而不是把两套 row/total/loading 再抄一遍
const arrears = usePagedList<ArrearsView>((q) => fetchArrears(q), { errorText: '欠费清单加载失败' })
const deposits = usePagedList<DepositView>((q) => fetchUnrefundedDeposits(q), {
  errorText: '押金清单加载失败',
})

// 必须解构到顶层：模板只会自动解**顶层**绑定的 ref，
// 直接写 `:data="arrears.rows"` 传进去的是 Ref 对象，el-table 会报
// `rows is not iterable` 并把整个页面渲染打断（build 和 lint 都发现不了这个）。
const { rows: arrearsRows } = arrears
const { rows: depositsRows } = deposits

const isArrears = computed(() => activeTab.value === 'arrears')
const total = computed(() => (isArrears.value ? arrears.total.value : deposits.total.value))
const loading = computed(() => (isArrears.value ? arrears.loading.value : deposits.loading.value))
const pageSize = computed(() => (isArrears.value ? arrears.page.pageSize : deposits.page.pageSize))
const pageNum = computed(() => (isArrears.value ? arrears.page.pageNum : deposits.page.pageNum))

function load(): Promise<void> {
  return isArrears.value ? arrears.load() : deposits.load()
}

function goTo(target: number): Promise<void> {
  return isArrears.value ? arrears.goTo(target) : deposits.goTo(target)
}

// 切 tab 回到第一页：两个清单的筛选条件不同，留在第 5 页没意义
watch(activeTab, () => {
  if (isArrears.value) arrears.search()
  else deposits.search()
})

// ── 客户状态：禁用（0）/ 启用（1），两个动作必须由人明确选 ─────────
const statusDialog = ref(false)
const statusForm = reactive<{ customerId: string; status: 0 | 1 | null; reason: string }>({
  customerId: '',
  status: null,
  reason: '',
})
const statusSubmitting = ref(false)

function openStatus(row: ArrearsView): void {
  statusForm.customerId = String(row.customerId ?? '')
  statusForm.status = null
  statusForm.reason = ''
  statusDialog.value = true
}

async function onSubmitStatus(): Promise<void> {
  if (statusForm.status === null) {
    ElMessage.warning('请选择要执行的动作：禁用或启用')
    return
  }
  if (!statusForm.reason.trim()) {
    ElMessage.warning('必须写明事由：无因的状态变更无法复盘')
    return
  }
  statusSubmitting.value = true
  try {
    await changeCustomerStatus(statusForm.customerId, {
      status: statusForm.status,
      reason: statusForm.reason,
    })
    ElMessage.success(statusForm.status === 0 ? '已禁用客户' : '已启用客户')
    statusDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    statusSubmitting.value = false
  }
}

onMounted(() => {
  if (allowedArrears.value || allowedDeposits.value) load()
})
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-tabs v-model="activeTab" class="tabs">
        <el-tab-pane v-if="allowedArrears" label="欠费客户" name="arrears" />
        <el-tab-pane v-if="allowedDeposits" label="未退押金" name="deposits" />
      </el-tabs>
      <div class="toolbar-right">
        <el-tag type="info" size="small">共 {{ total }} 条</el-tag>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <template v-if="activeTab === 'arrears'">
      <el-table v-loading="loading" :data="arrearsRows">
        <el-table-column label="客户 ID" prop="customerId" width="140" />
        <el-table-column label="欠费金额" width="140">
          <template #default="{ row }">
            <span class="danger">{{ pointsToYuan(row.arrearsPoints) }} 元</span>
          </template>
        </el-table-column>
        <el-table-column label="欠费单数" prop="arrearsOrders" width="110" />
        <el-table-column label="最近完成时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.lastFinishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasAuthority('locker:customer:disable')"
              link
              type="primary"
              @click="openStatus(row)"
            >
              调整状态
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有欠费客户" :image-size="60" />
        </template>
      </el-table>
    </template>

    <template v-else>
      <el-table v-loading="loading" :data="depositsRows">
        <el-table-column label="押金流水" prop="depositId" width="130" />
        <el-table-column label="订单号" prop="orderNo" min-width="170" show-overflow-tooltip />
        <el-table-column label="客户 ID" prop="customerId" width="140" />
        <el-table-column label="金额" width="130">
          <template #default="{ row }">{{ pointsToYuan(row.points) }} 元</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="depositStatusTag(row.depositStatus)" size="small">
              {{ depositStatusText(row.depositStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已持有时长" width="120">
          <template #default="{ row }">{{ row.heldHours ?? '—' }} 小时</template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有未退押金" :image-size="60" />
        </template>
      </el-table>
    </template>

    <PagerBar :total="total" :page-size="pageSize" :page-num="pageNum" @change="goTo" />
  </el-card>

  <el-dialog v-model="statusDialog" title="调整客户状态" width="520px">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="禁用后客户无法登录与下单（取件仍可走柜机屏）；启用后恢复。动作会进审计，必须写明事由。"
      class="status-alert"
    />

    <el-form label-width="96px">
      <el-form-item label="客户 ID">
        <span>{{ statusForm.customerId }}</span>
      </el-form-item>
      <el-form-item label="执行动作" required>
        <el-radio-group v-model="statusForm.status">
          <el-radio :value="0">禁用（禁止再下单）</el-radio>
          <el-radio :value="1">启用（恢复正常）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="事由" required>
        <el-input
          v-model="statusForm.reason"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="例如：拒付欠费、反复恶意下单、经核实后解除"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="statusDialog = false">取消</el-button>
      <el-button type="primary" :loading="statusSubmitting" @click="onSubmitStatus">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.tabs {
  flex: 1;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
}

.danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.muted {
  color: var(--el-text-color-secondary);
}

.status-alert {
  margin-bottom: 14px;
}
</style>

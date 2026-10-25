<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  fetchAnomalies,
  fetchAnomalySummary,
  fetchArrears,
  fetchFieldQueue,
  fetchOnlineQueue,
  fetchPendingCalls,
  fetchUnrefundedDeposits,
} from '@/api'
import type { NoticeView, SummaryView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { anomalyText, noticeKindText } from '@/utils/dict'
import { fmtMinutes, fmtTime } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()

const canLedger = computed(() => auth.hasAuthority('locker:ledger:list'))
const canCalls = computed(() => auth.hasAuthority('workorder:online:list'))
const canArrears = computed(() => auth.hasAuthority('locker:arrears:list'))
const canDeposit = computed(() => auth.hasAuthority('locker:deposit:unrefunded-list'))
const canOnline = computed(() => auth.hasAuthority('workorder:online:list'))
const canField = computed(() => auth.hasAuthority('workorder:field:list'))

const summary = ref<SummaryView[]>([])
const pendingCalls = ref<NoticeView[]>([])
const anomalies = ref(0)
const longestMinutes = ref<string | undefined>()
const onlineOpen = ref(0)
const fieldOpen = ref(0)
const arrearsCustomers = ref(0)
const unrefundedDeposits = ref(0)

const loading = reactive({
  anomaly: false,
  calls: false,
  orders: false,
  funds: false,
})

function fail(scope: string, e: unknown): void {
  ElMessage.error(`${scope}：${e instanceof ApiError ? e.message : '加载失败'}`)
}

async function loadAnomalies(): Promise<void> {
  if (!canLedger.value) return
  loading.anomaly = true
  try {
    const [rows, overview] = await Promise.all([
      fetchAnomalies({ pageNum: 1, pageSize: 1 }),
      fetchAnomalySummary(),
    ])
    anomalies.value = Number(rows.total ?? 0)
    summary.value = overview
    longestMinutes.value = overview.reduce<string | undefined>((max, row) => {
      const value = Number(row.longestMinutes ?? 0)
      return value > Number(max ?? 0) ? row.longestMinutes : max
    }, undefined)
  } catch (e) {
    fail('异常台账', e)
  } finally {
    loading.anomaly = false
  }
}

async function loadCalls(): Promise<void> {
  if (!canCalls.value) return
  loading.calls = true
  try {
    pendingCalls.value = await fetchPendingCalls()
  } catch (e) {
    fail('待电话任务', e)
  } finally {
    loading.calls = false
  }
}

async function loadOrders(): Promise<void> {
  loading.orders = true
  try {
    const tasks: Promise<void>[] = []
    if (canOnline.value) {
      tasks.push(
        fetchOnlineQueue({ pageNum: 1, pageSize: 1, openOnly: true }).then((page) => {
          onlineOpen.value = Number(page.total ?? 0)
        }),
      )
    }
    if (canField.value) {
      tasks.push(
        fetchFieldQueue({ pageNum: 1, pageSize: 1, openOnly: true }).then((page) => {
          fieldOpen.value = Number(page.total ?? 0)
        }),
      )
    }
    await Promise.all(tasks)
  } catch (e) {
    fail('工单队列', e)
  } finally {
    loading.orders = false
  }
}

async function loadFunds(): Promise<void> {
  loading.funds = true
  try {
    const tasks: Promise<void>[] = []
    if (canArrears.value) {
      tasks.push(
        fetchArrears({ pageNum: 1, pageSize: 1 }).then((page) => {
          arrearsCustomers.value = Number(page.total ?? 0)
        }),
      )
    }
    if (canDeposit.value) {
      tasks.push(
        fetchUnrefundedDeposits({ pageNum: 1, pageSize: 1 }).then((page) => {
          unrefundedDeposits.value = Number(page.total ?? 0)
        }),
      )
    }
    await Promise.all(tasks)
  } catch (e) {
    fail('资金待办', e)
  } finally {
    loading.funds = false
  }
}

function go(path: string): void {
  router.push(path)
}

onMounted(() => {
  loadAnomalies()
  loadCalls()
  loadOrders()
  loadFunds()
})
</script>

<template>
  <div class="dashboard">
    <div class="hero">
      <div>
        <h2 class="hero-title">今日运营</h2>
        <p class="hero-sub">
          先处理卡在最久的地方：异常格口、必须回填的电话、动过钱的处置。
        </p>
      </div>
      <div class="hero-user">{{ auth.account?.username ?? '' }}</div>
    </div>

    <el-row :gutter="12" class="stat-row">
      <el-col v-if="canLedger" :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat" @click="go('/anomalies')">
          <div class="stat-label">异常格口积压</div>
          <div v-loading="loading.anomaly" class="stat-value">{{ anomalies }} 格</div>
          <div class="stat-sub">最长滞留 {{ fmtMinutes(longestMinutes) }}</div>
        </el-card>
      </el-col>
      <el-col v-if="canCalls" :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat" @click="go('/pending-calls')">
          <div class="stat-label">必须打的电话</div>
          <div v-loading="loading.calls" class="stat-value">{{ pendingCalls.length }} 条</div>
          <div class="stat-sub">未回填前一直在列</div>
        </el-card>
      </el-col>
      <el-col v-if="canOnline || canField" :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat" @click="go('/work-orders')">
          <div class="stat-label">未关闭工单</div>
          <div v-loading="loading.orders" class="stat-value">{{ onlineOpen + fieldOpen }} 张</div>
          <div class="stat-sub">线上 {{ onlineOpen }} / 线下 {{ fieldOpen }}</div>
        </el-card>
      </el-col>
      <el-col v-if="canArrears || canDeposit" :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="stat" @click="go('/funds')">
          <div class="stat-label">资金待办</div>
          <div v-loading="loading.funds" class="stat-value">
            {{ arrearsCustomers + unrefundedDeposits }} 笔
          </div>
          <div class="stat-sub">欠费客户 {{ arrearsCustomers }} / 未退押金 {{ unrefundedDeposits }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <el-col :span="14">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-header">
              <span>异常积压分布</span>
              <el-button v-if="canLedger" link type="primary" @click="go('/anomalies')">
                去台账
              </el-button>
            </div>
          </template>
          <el-table v-if="summary.length" :data="summary" size="small">
            <el-table-column label="异常类型" width="150">
              <template #default="{ row }">{{ anomalyText(row.anomaly) }}</template>
            </el-table-column>
            <el-table-column label="积压条数" width="110">
              <template #default="{ row }">{{ row.rowsCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="最长滞留">
              <template #default="{ row }">{{ fmtMinutes(row.longestMinutes) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else :image-size="60" description="暂无积压异常" />
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-header">
              <span>待联系客户</span>
              <el-button v-if="canCalls" link type="primary" @click="go('/pending-calls')">
                全部任务
              </el-button>
            </div>
          </template>
          <el-table v-if="pendingCalls.length" :data="pendingCalls.slice(0, 5)" size="small">
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ noticeKindText(row.kind) }}</template>
            </el-table-column>
            <el-table-column label="内容" prop="content" show-overflow-tooltip />
            <el-table-column label="生成时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else :image-size="60" description="没有待回填的电话任务" />
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="never" class="panel">
          <template #header>快捷入口</template>
          <div class="shortcuts">
            <el-button v-if="canOnline || canField" @click="go('/work-orders')">工单队列</el-button>
            <el-button v-if="canLedger" @click="go('/anomalies')">异常台账</el-button>
            <el-button v-if="canCalls" @click="go('/pending-calls')">待联系客户</el-button>
            <el-button v-if="canArrears || canDeposit" @click="go('/funds')">资金运营</el-button>
            <el-button v-if="auth.hasAuthority('pricing:rule:list')" @click="go('/pricing')">
              定价策略
            </el-button>
            <el-button v-if="auth.hasAuthority('ops:diagnose')" @click="go('/ops')">
              运维诊断
            </el-button>
            <el-button v-if="auth.hasAuthority('system:user:list')" @click="go('/users')">
              账号列表
            </el-button>
          </div>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>口径提醒</template>
          <ul class="notes">
            <li>异常台账按“卡最久优先”：先处理滞留时间长的格口。</li>
            <li>电话任务没有外呼能力，打完必须回填；未回填会一直挂在队列里。</li>
            <li>强制开柜、清柜、禁用客户都要留下事由，事后按留痕复盘。</li>
            <li>AI 简报与诊断都只是草稿，执行动作永远由人确认。</li>
          </ul>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  padding-bottom: 24px;
}

.hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.hero-title {
  margin: 0;
  font-size: 22px;
}

.hero-sub {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hero-user {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.stat-row {
  margin-bottom: 0;
}

/* 窄屏下 6 列变 2 列/1 列，行间补间距，否则卡片会贴在一起 */
.stat-row .el-col {
  margin-bottom: 12px;
}

.stat {
  cursor: pointer;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.stat-value {
  margin: 6px 0 2px;
  font-size: 26px;
  font-weight: 600;
}

.stat-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.panel {
  margin-bottom: 12px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.shortcuts .el-button {
  margin-left: 0;
}

.notes {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.9;
}
</style>

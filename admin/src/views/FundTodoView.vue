<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'

import { changeCustomerStatus, listArrears, listUnrefundedDeposits, type ArrearsView, type DepositView } from '@/api/locker'
import type { PageResult } from '@/types/api'
import { useAuthStore } from '@/stores/auth'

/**
 * 资金待办（第 14E 刀）：未退押金 + 欠费客户。
 *
 * <p>两个清单放一页是刻意的：它们几乎都是同一件事的两面——"单结束了，钱还没回到用户账上"，
 * 值班的人一次看全，不用在两个菜单间跳。
 *
 * <p>路由本身不挂权限码，页内按 tab 控制可见性：<b>两个 tab 各需一个权限点</b>，
 * 用一个 perm 会误挡掉只有另一个权限的人；服务端仍逐接口判 `hasAuthority`。
 */
const auth = useAuthStore()

const canSeeDeposits = computed(() => auth.hasAuthority('locker:deposit:unrefunded-list'))
const canSeeArrears = computed(() => auth.hasAuthority('locker:arrears:list'))
const canDisable = computed(() => auth.hasAuthority('locker:customer:disable'))

const activeTab = ref(canSeeDeposits.value ? 'deposits' : 'arrears')

const loading = ref(false)
const deposits = ref<PageResult<DepositView>>({ pageNum: 1, pageSize: 20, total: 0, pages: 0, records: [] })
const arrears = ref<PageResult<ArrearsView>>({ pageNum: 1, pageSize: 20, total: 0, pages: 0, records: [] })

const pageQuery = reactive({ pageNum: 1, pageSize: 20 })

/** 1 点 = 1 分（S-01）：这里换算成元展示，别在页面上再算一遍业务口径 */
function yuan(points?: number): string {
  const value = Number(points ?? 0)
  return (value / 100).toFixed(2)
}

async function load() {
  loading.value = true
  try {
    const tasks: Promise<unknown>[] = []
    if (canSeeDeposits.value) {
      tasks.push(listUnrefundedDeposits({ ...pageQuery }).then((result) => (deposits.value = result)))
    }
    if (canSeeArrears.value) {
      tasks.push(listArrears({ ...pageQuery }).then((result) => (arrears.value = result)))
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
}

function onPage(pageNum: number) {
  pageQuery.pageNum = pageNum
  return load()
}

/**
 * 禁用客户。<b>事由必填</b>（服务端同样硬要求）：无因的状态变更事后无法复盘。
 * 与欠费拦截的分工要记清楚：欠费是系统自动挡、还清即自愈；禁用是人工判定、只能人工解除。
 */
async function onDisable(raw: unknown) {
  // 表格插槽拿到的是 DefaultRow，统一在此收一次类型；理由同异常台账页
  const row = raw as ArrearsView
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      `禁用后该客户无法登录也无法下单（取件不受影响）。请写明事由，事后要能复盘。`,
      `禁用客户 ${row.customerId}`,
      { inputValidator: (value: string) => (value && value.trim().length >= 4 ? true : '至少写 4 个字') },
    )
    reason = (result.value ?? '').trim()
  } catch {
    return
  }
  await changeCustomerStatus(row.customerId, 0, reason)
  ElMessage.success('已禁用该客户')
  return load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <ElTabs v-model="activeTab">
      <ElTabPane v-if="canSeeDeposits" name="deposits" :label="`未退押金（${deposits.total}）`">
        <ElTable v-loading="loading" :data="deposits.records" border>
          <ElTableColumn prop="orderNo" label="订单号" width="200" />
          <ElTableColumn prop="customerId" label="客户" width="180" />
          <ElTableColumn prop="points" label="押金（元）" width="120">
            <template #default="{ row }">{{ yuan(row.points) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="depositStatus" label="凭证状态" width="120" />
          <ElTableColumn prop="orderStatus" label="订单状态" width="120" />
          <ElTableColumn prop="heldHours" label="已挂多久" min-width="120">
            <template #default="{ row }">{{ row.heldHours ?? 0 }} 小时</template>
          </ElTableColumn>
        </ElTable>
        <p class="tip">
          只列订单已终态而押金仍未退还的：进行中的单挂着押金是正常态，不该出现在这里。退还重试由调度负责，本页只做发现。
        </p>
      </ElTabPane>

      <ElTabPane v-if="canSeeArrears" name="arrears" :label="`欠费客户（${arrears.total}）`">
        <ElTable v-loading="loading" :data="arrears.records" border>
          <ElTableColumn prop="customerId" label="客户" width="200" />
          <ElTableColumn prop="arrearsPoints" label="未缴（元）" width="130">
            <template #default="{ row }">{{ yuan(row.arrearsPoints) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="arrearsOrders" label="欠费单数" width="110" />
          <ElTableColumn prop="lastFinishedAt" label="最近一次结算" min-width="180" />
          <ElTableColumn v-if="canDisable" label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <ElButton link type="danger" @click="onDisable(row)">禁用</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <p class="tip">
          欠费客户已被服务端挡住下单（<code>欠费 &gt; 0</code>，无阈值，还清即恢复）；取件不受影响。
          禁用是针对拒付等恶意行为的人工处置，只能人工解除。
        </p>
      </ElTabPane>
    </ElTabs>

    <ElPagination
      v-if="canSeeDeposits || canSeeArrears"
      class="pager"
      layout="total, prev, pager, next"
      :total="activeTab === 'deposits' ? deposits.total : arrears.total"
      :page-size="pageQuery.pageSize"
      :current-page="pageQuery.pageNum"
      @current-change="onPage"
    />
  </div>
</template>

<style scoped>
.pager {
  margin-top: 12px;
}

.tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
</style>

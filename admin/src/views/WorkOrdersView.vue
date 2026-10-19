<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  aiBrief,
  claimWorkOrder,
  fetchEvidence,
  fetchFieldQueue,
  fetchOnlineQueue,
  fetchTimeline,
  phoneNote,
  resolveWorkOrder,
} from '@/api'
import type { Brief, EventView, EvidenceView } from '@/api/schema-admin'
import type { WorkOrderRow } from '@/api/contracts'
import { ApiError } from '@/api/http'
import {
  evidenceMomentText,
  evidenceSourceText,
  presenceText,
  queueSideText,
  workOrderEventText,
  workOrderStatusTag,
  workOrderStatusText,
  workOrderTypeText,
} from '@/utils/dict'
import { fmtTime, newRequestId } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const activeSide = ref<'ONLINE' | 'FIELD'>('ONLINE')

const loading = ref(false)
const rows = ref<WorkOrderRow[]>([])
const total = ref(0)
const pageQuery = reactive({ pageNum: 1, pageSize: 20 })

const canClaim = computed(() => auth.hasAuthority('workorder:claim'))
const canResolve = computed(() => auth.hasAuthority('workorder:resolve'))

function sideAllowed(side: 'ONLINE' | 'FIELD'): boolean {
  return auth.hasAnyAuthority(
    side === 'ONLINE' ? ['workorder:online:list'] : ['workorder:field:list'],
  )
}

function canClaimRow(row: WorkOrderRow): boolean {
  return canClaim.value && row.status === 'PENDING'
}

async function load(): Promise<void> {
  if (!sideAllowed(activeSide.value)) return
  loading.value = true
  try {
    const fetcher = activeSide.value === 'ONLINE' ? fetchOnlineQueue : fetchFieldQueue
    const page = await fetcher({ ...pageQuery, openOnly: true })
    rows.value = page.records ?? []
    total.value = Number(page.total ?? 0)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function switchSide(side: string | number): void {
  activeSide.value = side === 'FIELD' ? 'FIELD' : 'ONLINE'
  pageQuery.pageNum = 1
  load()
}

function onPageChange(pageNum: number): void {
  pageQuery.pageNum = pageNum
  load()
}

// ── 详情抽屉：留痕 + 证据 + AI 简报 ─────────────────────────────
const drawerOpen = ref(false)
const current = ref<WorkOrderRow | null>(null)
const timeline = ref<EventView[]>([])
const evidence = ref<EvidenceView[]>([])
const brief = ref<Brief | null>(null)
const briefLoading = ref(false)
const detailLoading = ref(false)

async function openDetail(row: WorkOrderRow): Promise<void> {
  current.value = row
  drawerOpen.value = true
  brief.value = null
  detailLoading.value = true
  try {
    const [tl, ev] = await Promise.all([fetchTimeline(String(row.id)), fetchEvidence(String(row.id))])
    timeline.value = tl ?? []
    evidence.value = ev ?? []
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

/** AI 简报不落库：只给当前业务员看，UI 不做"保存简报" */
async function loadBrief(): Promise<void> {
  if (!current.value) return
  briefLoading.value = true
  try {
    brief.value = await aiBrief(String(current.value.id))
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '简报生成失败')
  } finally {
    briefLoading.value = false
  }
}

// ── 认领 / 处理 / 电话留痕 ───────────────────────────────────────
async function onClaim(row: WorkOrderRow): Promise<void> {
  try {
    await claimWorkOrder(String(row.id))
    ElMessage.success('已认领')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '认领失败')
  }
}

const resolveDialog = ref(false)
const resolveForm = reactive({ resultNote: '', needOffline: false })
// 同一"处理意图"重试复用同一个 requestId：处理会派生子单，重放不能派出第二张
let resolveRequestId = ''

function openResolve(row: WorkOrderRow, draft = ''): void {
  current.value = row
  resolveForm.resultNote = draft
  resolveForm.needOffline = false
  resolveRequestId = newRequestId()
  resolveDialog.value = true
}

/** 把 AI 草稿填进结论框：草稿只是起点，提交前必须由人改过 */
function applyBriefToResolve(): void {
  if (!current.value || !brief.value) return
  openResolve(current.value, brief.value.draftNote ?? '')
}

async function onResolve(): Promise<void> {
  if (!current.value) return
  if (!resolveForm.resultNote.trim()) {
    ElMessage.warning('结论必填：没有结论说明的工单等于整个流转白做')
    return
  }
  try {
    await resolveWorkOrder(String(current.value.id), {
      requestId: resolveRequestId,
      resultNote: resolveForm.resultNote,
      needOffline: resolveForm.needOffline,
    })
    ElMessage.success('已处理')
    resolveDialog.value = false
    drawerOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '处理失败')
  }
}

const phoneDialog = ref(false)
const phoneForm = reactive({ resultNote: '' })

function openPhoneNote(row: WorkOrderRow): void {
  current.value = row
  phoneForm.resultNote = ''
  phoneDialog.value = true
}

async function onPhoneNote(): Promise<void> {
  if (!current.value) return
  if (!phoneForm.resultNote.trim()) {
    ElMessage.warning('联系结果必填：联系上了/未接/约定多久处理')
    return
  }
  try {
    await phoneNote(String(current.value.id), { resultNote: phoneForm.resultNote })
    ElMessage.success('已留痕')
    phoneDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '留痕失败')
  }
}

async function onClaimWithConfirm(row: WorkOrderRow): Promise<void> {
  try {
    await ElMessageBox.confirm('确认认领该工单？认领后由你负责推进。', '认领工单', { type: 'info' })
  } catch {
    return
  }
  await onClaim(row)
}

// 初始：按权限选第一个可看的队列
function initSide(): void {
  if (!sideAllowed('ONLINE') && sideAllowed('FIELD')) activeSide.value = 'FIELD'
}

onMounted(() => {
  initSide()
  load()
})
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-tabs v-model="activeSide" class="tabs" @tab-change="switchSide">
        <el-tab-pane v-if="sideAllowed('ONLINE')" label="线上队列" name="ONLINE" />
        <el-tab-pane v-if="sideAllowed('FIELD')" label="线下队列" name="FIELD" />
      </el-tabs>
      <div class="toolbar-right">
        <el-tag type="info" size="small">未关闭 {{ total }} 张</el-tag>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      :row-style="{ cursor: 'pointer' }"
      scrollbar-always-on
      @row-click="openDetail"
    >
      <el-table-column label="工单号" prop="id" width="90" />
      <el-table-column label="紧急" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.urgent" type="danger" size="small">紧急</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ workOrderTypeText(row.type) }}</template>
      </el-table-column>
      <el-table-column label="标题" prop="title" min-width="200" show-overflow-tooltip />
      <el-table-column label="柜机/格口" width="130">
        <template #default="{ row }">{{ row.cabinetNo ?? '—' }} / {{ row.slotNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="点位地址" prop="siteAddress" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="workOrderStatusTag(row.status)" size="small">
            {{ workOrderStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="截止时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.dueAt) }}</template>
      </el-table-column>
      <el-table-column label="开单时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="canClaimRow(row)"
            link
            type="primary"
            @click.stop="onClaimWithConfirm(row)"
          >
            认领
          </el-button>
          <el-button v-if="canResolve" link type="primary" @click.stop="openResolve(row)">
            处理
          </el-button>
          <el-button v-if="canResolve" link type="primary" @click.stop="openPhoneNote(row)">
            电话留痕
          </el-button>
          <span v-if="!canClaimRow(row) && !canResolve" class="muted">—</span>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="当前队列没有未关闭的工单" :image-size="60" />
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

  <el-drawer v-model="drawerOpen" size="660px" :title="`工单 #${current?.id ?? ''}`">
    <div v-loading="detailLoading">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="类型">{{ workOrderTypeText(current?.type) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="workOrderStatusTag(current?.status)" size="small">
            {{ workOrderStatusText(current?.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="队列">{{ queueSideText(current?.queueSide) }}</el-descriptions-item>
        <el-descriptions-item label="紧急">
          <el-tag v-if="current?.urgent" type="danger" size="small">紧急</el-tag>
          <span v-else class="muted">普通</span>
        </el-descriptions-item>
        <el-descriptions-item label="柜机/格口">
          {{ current?.cabinetNo ?? '—' }} / {{ current?.slotNo ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="关联订单">{{ current?.orderNo ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="客户电话">{{ current?.customerPhone ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="截止时间">{{ fmtTime(current?.dueAt) }}</el-descriptions-item>
        <el-descriptions-item label="点位地址" :span="2">
          {{ current?.siteAddress ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="检测描述" :span="2">
          {{ current?.detection ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="处理结论" :span="2">
          {{ current?.resultNote ?? '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <h4 class="section-title">AI 简报（仅供参考，不写回工单）</h4>
      <div class="brief-actions">
        <el-button :loading="briefLoading" @click="loadBrief">生成简报</el-button>
        <el-button v-if="brief" type="primary" plain @click="applyBriefToResolve">
          用草稿填处理结论
        </el-button>
      </div>
      <template v-if="brief">
        <p class="brief-text"><b>事件摘要：</b>{{ brief.summary }}</p>
        <p class="brief-text"><b>结论草稿：</b>{{ brief.draftNote }}</p>
        <el-table v-if="brief.similarCases?.length" :data="brief.similarCases" size="small">
          <el-table-column label="相似已结单" prop="title" show-overflow-tooltip />
          <el-table-column label="结论" prop="resultNote" show-overflow-tooltip />
          <el-table-column label="结单时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.resolvedAt) }}</template>
          </el-table-column>
        </el-table>
        <p v-else class="muted">没有找到相似已结单。</p>
      </template>

      <h4 class="section-title">流转留痕</h4>
      <el-timeline v-if="timeline.length">
        <el-timeline-item v-for="(event, index) in timeline" :key="index" :timestamp="fmtTime(event.at)">
          <b>{{ workOrderEventText(event.eventType) }}</b>
          <span class="event-detail">{{ event.detail ?? '' }}</span>
          <span v-if="event.operatorId" class="muted">（操作人 {{ event.operatorId }}）</span>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else :image-size="50" description="暂无留痕" />

      <h4 class="section-title">格口证据时间线</h4>
      <el-table v-if="evidence.length" :data="evidence" size="small">
        <el-table-column label="采集点" width="110">
          <template #default="{ row }">{{ evidenceMomentText(row.moment) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ evidenceSourceText(row.source) }}</template>
        </el-table-column>
        <el-table-column label="结论" width="90">
          <template #default="{ row }">{{ presenceText(row.presence) }}</template>
        </el-table-column>
        <el-table-column label="AI 标签" prop="aiLabel" width="120" show-overflow-tooltip />
        <el-table-column label="备注" prop="note" show-overflow-tooltip />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.at) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else :image-size="50" description="暂无证据记录" />

      <div class="drawer-actions">
        <el-button
          v-if="current && canClaimRow(current)"
          @click="onClaimWithConfirm(current)"
        >
          认领
        </el-button>
        <el-button v-if="canResolve && current" type="primary" @click="openResolve(current)">
          处理
        </el-button>
        <el-button v-if="canResolve && current" @click="openPhoneNote(current)">电话留痕</el-button>
      </div>
    </div>
  </el-drawer>

  <el-dialog v-model="resolveDialog" title="处理并反馈" width="520px">
    <el-form label-width="96px">
      <el-form-item label="结论" required>
        <el-input
          v-model="resolveForm.resultNote"
          type="textarea"
          :rows="5"
          maxlength="400"
          show-word-limit
          placeholder="写清现场事实与处置结果；AI 草稿可用，但必须自己核对"
        />
      </el-form-item>
      <el-form-item label="派线下单">
        <el-switch v-model="resolveForm.needOffline" active-text="需要跑一趟时另发一张线下工单" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="resolveDialog = false">取消</el-button>
      <el-button type="primary" @click="onResolve">提交</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="phoneDialog" title="记录电话联系" width="520px">
    <el-form label-width="96px">
      <el-form-item label="联系结果" required>
        <el-input
          v-model="phoneForm.resultNote"
          type="textarea"
          :rows="3"
          maxlength="300"
          show-word-limit
          placeholder="联系上了 / 未接 / 约定多久处理"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="phoneDialog = false">取消</el-button>
      <el-button type="primary" @click="onPhoneNote">提交</el-button>
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

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.section-title {
  margin: 20px 0 10px;
}

.brief-actions {
  display: flex;
  gap: 8px;
}

.brief-text {
  margin: 8px 0;
  line-height: 1.7;
}

.event-detail {
  margin-left: 6px;
  color: var(--el-text-color-regular);
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.drawer-actions {
  margin-top: 20px;
  display: flex;
  gap: 8px;
}
</style>

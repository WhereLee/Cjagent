<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import axios from 'axios'
import { fetchDeadLetters, opsAsk, opsDiagnose, replayDeadLetter, waitAiJob } from '@/api'
import type { OpsAnswer, OpsReport } from '@/api/ai-results'
import type { DeadLetterView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const diagnosing = ref(false)
const report = ref<OpsReport | null>(null)
const elapsed = ref(0)
const asking = ref(false)
const question = ref('')
const answer = ref('')

let timer: number | undefined
let diagnoseController: AbortController | null = null
let askController: AbortController | null = null

function startTimer(): void {
  stopTimer()
  elapsed.value = 0
  timer = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
}

function stopTimer(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function elapsedText(): string {
  const minutes = Math.floor(elapsed.value / 60)
  const seconds = elapsed.value % 60
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
}

function isCanceled(e: unknown): boolean {
  return axios.isCancel(e) || (e instanceof Error && e.message === '已取消')
}

/** AI 诊断是长任务：提交毫秒级返回作业号，报告在后台生成；限流 3 次/分 */
async function onDiagnose(): Promise<void> {
  diagnoseController = new AbortController()
  diagnosing.value = true
  report.value = null
  startTimer()
  try {
    // 提交只回作业号；报告由作业通道在后台生成，这里轮询取回
    const jobId = await opsDiagnose(diagnoseController.signal)
    report.value = await waitAiJob<OpsReport>(
      jobId,
      60_000,
      () => diagnoseController?.signal.aborted === true,
    )
  } catch (e) {
    if (isCanceled(e)) {
      ElMessage.info('已取消诊断')
    } else {
      ElMessage.error(e instanceof ApiError ? e.message : '诊断失败')
    }
  } finally {
    stopTimer()
    diagnosing.value = false
    diagnoseController = null
  }
}

function onCancelDiagnose(): void {
  diagnoseController?.abort()
}

async function onAsk(): Promise<void> {
  if (!question.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  askController = new AbortController()
  asking.value = true
  answer.value = ''
  try {
    // 同上：提交拿作业号，轮询取回答案
    const jobId = await opsAsk({ question: question.value }, askController.signal)
    const result = await waitAiJob<OpsAnswer>(
      jobId,
      60_000,
      () => askController?.signal.aborted === true,
    )
    answer.value = result.answer ?? ''
  } catch (e) {
    if (isCanceled(e)) {
      ElMessage.info('已取消提问')
    } else {
      ElMessage.error(e instanceof ApiError ? e.message : '问答失败')
    }
  } finally {
    asking.value = false
    askController = null
  }
}

function onCancelAsk(): void {
  askController?.abort()
}

onBeforeUnmount(() => {
  stopTimer()
  diagnoseController?.abort()
  askController?.abort()
})

// ── 死信（MQ） ─────────────────────────────────────────────────

const deadLetters = ref<DeadLetterView[]>([])
const deadLetterTotal = ref(0)
const deadLetterLoading = ref(false)
/** 默认只看待处理：已重放的不应该和“还没人管”的摆在同一个默认视图里 */
const deadLetterStatus = ref('OPEN')
const deadLetterPage = reactive({ pageNum: 1, pageSize: 10 })
const detail = ref<DeadLetterView | null>(null)
const detailOpen = ref(false)
const replayingId = ref<string | null>(null)

async function loadDeadLetters(): Promise<void> {
  if (!auth.hasAuthority('ops:diagnose')) return
  deadLetterLoading.value = true
  try {
    const page = await fetchDeadLetters({
      pageNum: deadLetterPage.pageNum,
      pageSize: deadLetterPage.pageSize,
      status: deadLetterStatus.value || undefined,
    })
    deadLetters.value = page.records ?? []
    deadLetterTotal.value = Number(page.total ?? 0)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '死信加载失败')
  } finally {
    deadLetterLoading.value = false
  }
}

function onDeadLetterFilter(): void {
  deadLetterPage.pageNum = 1
  loadDeadLetters()
}

function openDetail(row: DeadLetterView): void {
  detail.value = row
  detailOpen.value = true
}

async function onReplay(row: DeadLetterView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `重放会把这条消息重新投回 ${row.topic}，由消费组再处理一次。\n` +
        '重复处理本身是安全的（业务侧靠唯一索引兜底），但它会真的再跑一遍业务逻辑。',
      '重放这条死信？',
      { type: 'warning', confirmButtonText: '重放', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  replayingId.value = String(row.id)
  try {
    const ok = await replayDeadLetter(String(row.id))
    if (ok) {
      ElMessage.success('已重放：消息已投回原 topic，消费组会再处理一次')
      detailOpen.value = false
      await loadDeadLetters()
    } else {
      // false 不是错误：已重放过，或坏消息（缺租户）不可重放
      ElMessage.warning('没有重放：可能已重放过，或这条消息缺租户信息（坏消息只能人工看）')
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '重放失败')
  } finally {
    replayingId.value = null
  }
}

onMounted(() => {
  loadDeadLetters()
})
</script>

<template>
  <el-row :gutter="12">
    <el-col :span="14">
      <el-card class="panel">
        <template #header>
          <div class="panel-header">
            <span>AI 运维诊断</span>
            <div class="header-actions">
              <span v-if="diagnosing" class="elapsed">已用 {{ elapsedText() }}</span>
              <el-button v-if="diagnosing" @click="onCancelDiagnose">取消</el-button>
              <el-button
                v-if="auth.hasAuthority('ops:diagnose') && !diagnosing"
                type="primary"
                @click="onDiagnose"
              >
                开始诊断
              </el-button>
            </div>
          </div>
        </template>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="诊断会读取健康状态、两类日志、崩溃残留与台账，通常要几十秒到几分钟；进行中可点右上角取消。"
          class="hint-alert"
        />

        <template v-if="report">
          <h4 class="section-title">健康摘要</h4>
          <p class="text">{{ report.healthSummary ?? '—' }}</p>

          <h4 class="section-title">日志发现</h4>
          <ul v-if="report.logFindings?.length" class="text">
            <li v-for="(finding, index) in report.logFindings" :key="index">{{ finding }}</li>
          </ul>
          <p v-else class="muted">没有发现异常日志。</p>

          <h4 class="section-title">崩溃残留</h4>
          <ul v-if="report.crashArtifacts?.length" class="text">
            <li v-for="(artifact, index) in report.crashArtifacts" :key="index">{{ artifact }}</li>
          </ul>
          <p v-else class="muted">没有崩溃残留。</p>

          <h4 class="section-title">建议（草稿）</h4>
          <ul v-if="report.advice?.length" class="text">
            <li v-for="(item, index) in report.advice" :key="index">{{ item }}</li>
          </ul>
          <p v-else class="muted">没有给出建议。</p>

          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="建议只是草稿：重启、清理、改配置永远由运维按手册手动执行，AI 不会也不应该自己动手。"
            class="hint-alert bottom-alert"
          />
        </template>

        <el-empty v-else-if="!diagnosing" description="点击右上角开始诊断" :image-size="60" />
      </el-card>
    </el-col>

    <el-col :span="10">
      <el-card class="panel">
        <template #header>
          <div class="panel-header">
            <span>运维问答</span>
            <el-button v-if="asking" @click="onCancelAsk">取消</el-button>
          </div>
        </template>
        <el-input
          v-model="question"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="例如：最近出过什么故障？GC 有没有异常？"
        />
        <el-button type="primary" :loading="asking" class="ask-btn" @click="onAsk">提问</el-button>
        <p v-if="asking" class="muted">正在查询只读工具并归纳事实…</p>
        <div v-if="answer" class="answer">{{ answer }}</div>
      </el-card>
    </el-col>

    <el-col :span="24">
      <el-card class="panel">
        <template #header>
          <div class="panel-header">
            <span>死信（MQ）</span>
            <div class="header-actions">
              <el-select v-model="deadLetterStatus" class="status-select" @change="onDeadLetterFilter">
                <el-option label="待处理" value="OPEN" />
                <el-option label="已重放" value="REPLAYED" />
                <el-option label="全部" value="" />
              </el-select>
              <el-button :icon="Refresh" :loading="deadLetterLoading" @click="loadDeadLetters">
                刷新
              </el-button>
              <el-tag v-if="deadLetterTotal > 0" :type="deadLetterStatus === 'OPEN' ? 'warning' : 'info'" size="small">
                {{ deadLetterTotal }} 条
              </el-tag>
            </div>
          </div>
        </template>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="死信 = 重试 16 次仍没处理成的事实。MQ 只留 3 天，这里永久归档；点“查看”能看消息正文，确认后再重放。"
          class="hint-alert"
        />

        <el-table v-loading="deadLetterLoading" :data="deadLetters" scrollbar-always-on>
          <el-table-column label="进入死信" width="170">
            <template #default="{ row }">{{ fmtTime(row.receivedAt) }}</template>
          </el-table-column>
          <el-table-column label="链路" width="160">
            <template #default="{ row }">
              <el-tag size="small" :type="row.tag === 'WORK_ORDER_ANOMALY' ? 'danger' : 'warning'">
                {{ row.tag === 'WORK_ORDER_ANOMALY' ? '开单' : '通知' }}
              </el-tag>
              <span class="muted key-text">{{ row.tag }}</span>
            </template>
          </el-table-column>
          <el-table-column label="消息键" prop="msgKey" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.msgKey || '—（坏消息无键）' }}</template>
          </el-table-column>
          <el-table-column label="重试" prop="reconsumeTimes" width="80" />
          <el-table-column label="原因" prop="error" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'OPEN' ? 'warning' : 'success'">
                {{ row.status === 'OPEN' ? '待处理' : '已重放' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">查看</el-button>
              <el-button
                v-if="row.status === 'OPEN' && auth.hasAuthority('ops:diagnose')"
                link
                type="warning"
                :loading="replayingId === String(row.id)"
                @click="onReplay(row)"
              >
                重放
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty
              :description="deadLetterStatus === 'OPEN' ? '没有待处理的死信：队列健康' : '没有匹配的死信'"
              :image-size="60"
            />
          </template>
        </el-table>

        <el-pagination
          v-if="deadLetterTotal > deadLetterPage.pageSize"
          class="pager"
          layout="total, prev, pager, next"
          :total="deadLetterTotal"
          :page-size="deadLetterPage.pageSize"
          :current-page="deadLetterPage.pageNum"
          @current-change="(p: number) => { deadLetterPage.pageNum = p; loadDeadLetters() }"
        />
      </el-card>
    </el-col>
  </el-row>

  <el-drawer v-model="detailOpen" title="死信详情" size="46%">
    <template v-if="detail">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="进入死信">{{ fmtTime(detail.receivedAt) }}</el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ detail.reconsumeTimes }}</el-descriptions-item>
        <el-descriptions-item label="哪条链路">{{ detail.dlqTopic }}</el-descriptions-item>
        <el-descriptions-item label="重放目标">{{ detail.topic }} / {{ detail.tag }}</el-descriptions-item>
        <el-descriptions-item label="消息键">{{ detail.msgKey || '—（无）' }}</el-descriptions-item>
        <el-descriptions-item label="失败原因">{{ detail.error || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.replayedAt" label="重放于">
          {{ fmtTime(detail.replayedAt) }}
        </el-descriptions-item>
      </el-descriptions>

      <h4 class="section-title">消息正文</h4>
      <pre class="payload">{{ detail.payload }}</pre>

      <el-alert
        v-if="detail.status !== 'OPEN'"
        type="success"
        :closable="false"
        show-icon
        title="这条已经重放过，不需要再动。"
        class="hint-alert bottom-alert"
      />
      <template v-else>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="重放会真的再跑一遍业务逻辑（工单靠唯一索引、通知靠 dedup_key 兜底，重复处理安全）。"
          class="hint-alert bottom-alert"
        />
        <el-button
          type="warning"
          :loading="replayingId === String(detail.id)"
          class="replay-btn"
          @click="onReplay(detail)"
        >
          重放这条
        </el-button>
      </template>
    </template>
  </el-drawer>
</template>

<style scoped>
.panel {
  margin-bottom: 12px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.elapsed {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section-title {
  margin: 16px 0 8px;
}

.text {
  line-height: 1.8;
  margin: 4px 0;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint-alert {
  margin-bottom: 6px;
}

.bottom-alert {
  margin-top: 16px;
}

.ask-btn {
  margin-top: 10px;
}

.answer {
  margin-top: 12px;
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 4px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.status-select {
  width: 110px;
}

.key-text {
  margin-left: 6px;
  font-size: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.payload {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  line-height: 1.6;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.replay-btn {
  margin-top: 12px;
}
</style>

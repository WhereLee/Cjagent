<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { opsAsk, opsDiagnose } from '@/api'
import type { OpsReport } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
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
  return axios.isCancel(e)
}

/** AI 诊断是分钟级慢接口：加载文案按分钟级预期，限流 3 次/分 */
async function onDiagnose(): Promise<void> {
  diagnoseController = new AbortController()
  diagnosing.value = true
  report.value = null
  startTimer()
  try {
    report.value = await opsDiagnose(diagnoseController.signal)
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
    const res = await opsAsk({ question: question.value }, askController.signal)
    answer.value = res.answer ?? ''
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
  </el-row>
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
</style>

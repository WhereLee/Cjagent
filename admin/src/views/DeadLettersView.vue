<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { fetchDeadLetters, replayDeadLetter } from '@/api'
import type { DeadLetterView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

// 死信是"重试耗尽仍没处理成的事实"，属于运维面，所以独立成页而不是塞在诊断页里：
// 诊断是"生成一份报告给你看"，死信是"有一堆东西等你处理"，两件事的心智模型不一样。
const auth = useAuthStore()
const rows = ref<DeadLetterView[]>([])
const total = ref(0)
const loading = ref(false)
/** 默认只看待处理：已重放的不该和"还没人管"的混在同一个默认视图里 */
const status = ref('OPEN')
const page = reactive({ pageNum: 1, pageSize: 20 })
const detail = ref<DeadLetterView | null>(null)
const detailOpen = ref(false)
const replayingId = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchDeadLetters({
      pageNum: page.pageNum,
      pageSize: page.pageSize,
      status: status.value || undefined,
    })
    rows.value = result.records ?? []
    total.value = Number(result.total ?? 0)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '死信加载失败')
  } finally {
    loading.value = false
  }
}

function onFilter(): void {
  page.pageNum = 1
  load()
}

function openDetail(row: DeadLetterView): void {
  detail.value = row
  detailOpen.value = true
}

async function onReplay(row: DeadLetterView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `重放会把这条消息重新投回 ${row.topic}，由消费组再处理一次。\n` +
        '重复处理本身是安全的（工单靠唯一索引、通知靠 dedup_key 兜底），但它会真的再跑一遍业务逻辑。',
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
      await load()
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

onMounted(load)
</script>

<template>
  <el-card>
    <el-form inline>
      <el-form-item label="状态">
        <el-select v-model="status" class="status-select" @change="onFilter">
          <el-option label="待处理" value="OPEN" />
          <el-option label="已重放" value="REPLAYED" />
          <el-option label="全部" value="" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-tag :type="status === 'OPEN' && total > 0 ? 'warning' : 'info'" size="small" class="count-tag">
          {{ status === 'OPEN' ? '待处理' : '合计' }} {{ total }} 条
        </el-tag>
      </el-form-item>
    </el-form>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="死信 = 重试 16 次仍没处理成的事实。MQ 只留 3 天，这里永久归档；点“查看”能看到消息正文，确认后再重放。"
      class="hint-alert"
    />

    <el-table v-loading="loading" :data="rows" scrollbar-always-on>
      <el-table-column label="进入死信" width="170">
        <template #default="{ row }">{{ fmtTime(row.receivedAt) }}</template>
      </el-table-column>
      <el-table-column label="链路" width="170">
        <template #default="{ row }">
          <el-tag size="small" :type="row.tag === 'WORK_ORDER_ANOMALY' ? 'danger' : 'warning'">
            {{ row.tag === 'WORK_ORDER_ANOMALY' ? '开单' : '通知' }}
          </el-tag>
          <span class="muted tag-text">{{ row.tag }}</span>
        </template>
      </el-table-column>
      <el-table-column label="消息键" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.msgKey || '—（坏消息无键）' }}</template>
      </el-table-column>
      <el-table-column label="重试" prop="reconsumeTimes" width="80" />
      <el-table-column label="原因" prop="error" min-width="200" show-overflow-tooltip />
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
          :description="status === 'OPEN' ? '没有待处理的死信：队列健康' : '没有匹配的死信'"
          :image-size="60"
        />
      </template>
    </el-table>

    <el-pagination
      v-if="total > page.pageSize"
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="page.pageSize"
      :current-page="page.pageNum"
      @current-change="(p: number) => { page.pageNum = p; load() }"
    />
  </el-card>

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
.status-select {
  width: 120px;
}

.count-tag {
  margin-left: 8px;
}

.hint-alert {
  margin-bottom: 12px;
}

.tag-text {
  margin-left: 6px;
  font-size: 12px;
}

.muted {
  color: var(--el-text-color-secondary);
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.section-title {
  margin: 16px 0 8px;
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

.bottom-alert {
  margin-top: 16px;
}

.replay-btn {
  margin-top: 12px;
}
</style>

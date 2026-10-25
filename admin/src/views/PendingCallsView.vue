<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { callDone, fetchPendingCalls } from '@/api'
import type { NoticeView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { noticeChannelText, noticeKindText } from '@/utils/dict'
import { fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<NoticeView[]>([])

const dialogOpen = ref(false)
const form = reactive({ resultNote: '' })
const current = ref<NoticeView | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await fetchPendingCalls()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openDone(row: NoticeView): void {
  current.value = row
  form.resultNote = ''
  dialogOpen.value = true
}

async function onDone(): Promise<void> {
  if (!current.value) return
  if (!form.resultNote.trim()) {
    ElMessage.warning('联系结果必填：联系上了/未接/约定多久处理')
    return
  }
  try {
    await callDone(String(current.value.id), { resultNote: form.resultNote })
    ElMessage.success('已回填')
    dialogOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '回填失败')
  }
}

onMounted(() => {
  if (auth.hasAuthority('workorder:online:list')) load()
})
</script>

<template>
  <el-card>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="阶梯升到“必须处理”时生成的电话任务。系统没有外呼能力，打完必须回填结果；未回填前会一直留在队列里。"
    />

    <div class="toolbar">
      <el-tag type="info" size="small">待回填 {{ rows.length }} 条</el-tag>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="任务号" prop="id" width="90" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ noticeKindText(row.kind) }}</template>
      </el-table-column>
      <el-table-column label="渠道" width="100">
        <template #default="{ row }">{{ noticeChannelText(row.channel) }}</template>
      </el-table-column>
      <el-table-column label="内容" prop="content" min-width="280" show-overflow-tooltip />
      <el-table-column label="关联订单" prop="orderNo" width="160" />
      <el-table-column label="关联工单" prop="workOrderId" width="100" />
      <el-table-column label="生成时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDone(row)">回填结果</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有待回填的电话任务" :image-size="60" />
      </template>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogOpen" title="回填电话结果" width="520px">
    <el-form label-width="96px">
      <el-form-item label="联系结果" required>
        <el-input
          v-model="form.resultNote"
          type="textarea"
          :rows="3"
          maxlength="300"
          show-word-limit
          placeholder="联系上了 / 未接 / 约定多久处理"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogOpen = false">取消</el-button>
      <el-button type="primary" @click="onDone">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0;
}
</style>

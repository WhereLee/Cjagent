<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchHealth, type HealthPayload } from '@/api/system'

const health = ref<HealthPayload | null>(null)
const loading = ref(false)
const error = ref('')

/**
 * 中间件状态：直接读后端 /api/system/health。
 *
 * 这里展示的是**组件级 status 原文**而不是自己推断"整体好不好"——
 * 判定权在服务端（含 liveness/readiness 分组），前端重复一套判断早晚和后端不一致。
 */
async function reload() {
  loading.value = true
  error.value = ''
  try {
    health.value = await fetchHealth()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '获取失败'
    health.value = null
  } finally {
    loading.value = false
  }
}

function rows(payload: HealthPayload | null) {
  const components = payload?.components ?? {}
  return Object.entries(components)
    .filter(([name]) => name !== 'livenessState' && name !== 'readinessState' && name !== 'ping')
    .map(([name, item]) => ({ name, status: item.status, detail: brief(item.details) }))
}

function brief(details?: Record<string, unknown>): string {
  if (!details) return '-'
  return Object.entries(details)
    .slice(0, 3)
    .map(([key, value]) => `${key}=${Array.isArray(value) ? value.join(',') : String(value)}`)
    .join('，')
    .slice(0, 120)
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h3 class="h">运行状态</h3>
      <ElButton :loading="loading" @click="reload">刷新</ElButton>
      <ElTag v-if="health" :type="health.status === 'UP' ? 'success' : 'danger'">
        总体 {{ health.status }}
      </ElTag>
    </div>

    <ElAlert v-if="error" :title="error" type="warning" show-icon :closable="false" />

    <ElTable v-if="!error" v-loading="loading" :data="rows(health)" border>
      <ElTableColumn prop="name" label="组件" width="180" />
      <ElTableColumn prop="status" label="状态" width="120">
        <template #default="{ row }">
          <ElTag :type="row.status === 'UP' ? 'success' : 'danger'">{{ row.status }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="detail" label="明细" show-overflow-tooltip />
    </ElTable>

    <p class="tip">
      数据源：<code>/api/system/health</code>（MySQL / PG / Redis / RocketMQ 四项由各自
      HealthIndicator 上报，RocketMQ 会真查 broker 是否已注册，不只测端口）
    </p>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.h {
  margin: 0;
}

.tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
</style>

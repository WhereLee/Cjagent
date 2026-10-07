<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchHealth, type HealthComponent, type HealthPayload } from '@/api/system'

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

/**
 * 健康检查的组件树只有一层有意义嵌套（db 下面挂两个数据源），这里展平到叶子。
 *
 * 只渲顶层时 MySQL 与 PG 会消失在 `db` 这一行里（它们的 `db.components.*` 被丢弃），
 * 而页面文案又声称展示这四项——**界面自己说自己的谎**。现在输出 `db.mysqlDataSource` 这种
 * 带路径的名字：既保留层级来源，又不再只拆顶层。容器行不再单独占一行（它只是聚合值）。
 */
const EXCLUDED_COMPONENTS = new Set([
  'livenessState',
  'readinessState',
  'ping',
  // Spring 自带的 SSL 指示器：本项目没有客户端证书链，明细永远是空值，
  // 摆在“中间件状态”表里只会让人误以为没取到数据
  'ssl',
])

function rows(payload: HealthPayload | null) {
  const out: { name: string; status: string; detail: string }[] = []
  flatten(payload?.components, '', out)
  return out
}

function flatten(components: Record<string, HealthComponent> | undefined, prefix: string, out: { name: string; status: string; detail: string }[]) {
  if (!components) return
  for (const [name, item] of Object.entries(components)) {
    if (EXCLUDED_COMPONENTS.has(name)) continue
    const label = prefix ? `${prefix}.${name}` : name
    const children = item.components
    if (children && Object.keys(children).length > 0) {
      flatten(children, label, out)
    } else {
      out.push({ name: label, status: item.status, detail: brief(item.details) })
    }
  }
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
      <ElTableColumn prop="name" label="组件" width="220" />
      <ElTableColumn prop="status" label="状态" width="120">
        <template #default="{ row }">
          <ElTag :type="row.status === 'UP' ? 'success' : 'danger'">{{ row.status }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="detail" label="明细" show-overflow-tooltip />
    </ElTable>

    <p class="tip">
      数据源：<code>/api/system/health</code>。组件树已展平到叶子（所以 MySQL 与 PG 是
      <code>db.mysqlDataSource</code> / <code>db.pgDataSource</code> 两行），
      liveness/readiness/ping/ssl 这类与中间件无关的系统组件不展示；RocketMQ 会真查 broker 是否已注册，不只测端口。
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

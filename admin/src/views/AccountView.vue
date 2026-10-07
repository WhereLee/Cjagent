<script setup lang="ts">
import { computed } from 'vue'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const account = computed(() => auth.account)

/** 把成品权限串拆成"角色"与"权限点"两组，界面读起来比一长串字符串清楚 */
const roles = computed(() => (account.value?.authorities ?? []).filter((a) => a.startsWith('ROLE_')))
const perms = computed(() => (account.value?.authorities ?? []).filter((a) => !a.startsWith('ROLE_')))
</script>

<template>
  <div class="page">
    <ElDescriptions :column="2" border>
      <ElDescriptionsItem label="账号 ID">
        <span class="mono">{{ account?.userId ?? '-' }}</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="用户名">{{ account?.username ?? '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="租户 ID">
        <span class="mono">{{ account?.tenantId ?? '-' }}</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="端">{{ account?.end ?? '-' }}</ElDescriptionsItem>
    </ElDescriptions>

    <h4 class="h">角色</h4>
    <ElSpace wrap>
      <ElTag v-for="role in roles" :key="role" type="warning">{{ role }}</ElTag>
      <span v-if="!roles.length" class="empty">无</span>
    </ElSpace>

    <h4 class="h">权限点</h4>
    <ElSpace wrap>
      <ElTag v-for="perm in perms" :key="perm">{{ perm }}</ElTag>
      <span v-if="!perms.length" class="empty">无</span>
    </ElSpace>

    <p class="tip">
      ID 以字符串显示是刻意的：后端雪花 ID 有 19 位，超过 JS 安全整数，按数字解析会静默失真。
    </p>
  </div>
</template>

<style scoped>
.h {
  margin: 20px 0 8px;
}

.empty {
  color: #909399;
}

.tip {
  margin-top: 20px;
  font-size: 12px;
  color: #909399;
}
</style>

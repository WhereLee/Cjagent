<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <ElResult icon="info" title="404" sub-title="页面不存在">
    <template #extra>
      <ElButton type="primary" @click="router.replace('/dashboard')">回到首页</ElButton>
    </template>
  </ElResult>
</template>

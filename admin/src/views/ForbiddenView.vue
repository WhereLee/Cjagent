<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <ElResult icon="warning" title="403" sub-title="当前账号没有该功能的权限">
    <template #extra>
      <ElButton type="primary" @click="router.replace('/dashboard')">回到首页</ElButton>
    </template>
  </ElResult>
</template>

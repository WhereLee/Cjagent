<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="403" sub-title="当前账号没有该功能的权限点，请联系管理员授权或更换账号">
    <template #extra>
      <el-button type="primary" @click="router.push('/dashboard')">返回工作台</el-button>
    </template>
  </el-result>
</template>

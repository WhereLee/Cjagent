<script setup lang="ts">
// 按需引入下没有全局 app.use(ElementPlus)，语言包靠 ConfigProvider 注入。
// 注意：直接 import 使用的 ElMessage 不走组件解析，它的样式在 http.ts 里单独引入。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <RouterView />
  </ElConfigProvider>
</template>

<script setup lang="ts">
/**
 * 分页条：六个页面原本各写一段 `el-pagination`，属性与事件完全一致。
 * 只有一页数据时不显示，避免"共 3 条"占一整行却没得翻。
 */
defineProps<{ total: number; pageSize: number; pageNum: number }>()

const emit = defineEmits<{ change: [pageNum: number] }>()
</script>

<template>
  <el-pagination
    v-if="total > pageSize"
    class="pager"
    layout="total, prev, pager, next"
    :total="total"
    :page-size="pageSize"
    :current-page="pageNum"
    @current-change="(p: number) => emit('change', p)"
  />
</template>

<style scoped>
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>

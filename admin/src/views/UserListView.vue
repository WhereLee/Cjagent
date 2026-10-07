<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'

import { listUsers, USER_SORTABLE, type UserView } from '@/api/user'
import type { PageResult } from '@/types/api'

const loading = ref(false)
const page = ref<PageResult<UserView>>({ pageNum: 1, pageSize: 20, total: 0, pages: 0, records: [] })

const query = reactive({
  keyword: '',
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 20,
  orderBy: 'createTime' as string | undefined,
  asc: false,
})

/** 只有白名单里的列才渲染成可排序列，避免用户点了个后端会 400 的字段 */
const sortable = new Set<string>(USER_SORTABLE)

async function load() {
  loading.value = true
  try {
    page.value = await listUsers({
      keyword: query.keyword || undefined,
      status: query.status,
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      orderBy: query.orderBy,
      asc: query.asc,
    })
  } finally {
    loading.value = false
  }
}

/**
 * 签名要跟 Element Plus 的 sort-change 事件一致：prop 与 order 都可能为 null
 * （取消排序时）。这里写宽了会在 typecheck 上报不兼容，写窄了会漏掉 null 分支。
 */
function onSortChange(payload: { prop: string | null; order: 'ascending' | 'descending' | null }) {
  query.pageNum = 1
  if (!payload.order || !payload.prop) {
    query.orderBy = undefined
    return load()
  }
  query.orderBy = payload.prop
  query.asc = payload.order === 'ascending'
  return load()
}

function onSearch() {
  query.pageNum = 1
  return load()
}

function onSizeChange() {
  query.pageNum = 1
  return load()
}

function reset() {
  query.keyword = ''
  query.status = undefined
  query.pageNum = 1
  return load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <ElForm inline @submit.prevent>
      <ElFormItem label="关键字">
        <ElInput v-model.trim="query.keyword" placeholder="用户名 / 姓名" clearable style="width: 200px" />
      </ElFormItem>
      <ElFormItem label="状态">
        <ElSelect v-model="query.status" clearable placeholder="全部" style="width: 120px">
          <ElOption :value="1" label="启用" />
          <ElOption :value="0" label="停用" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem>
        <ElButton type="primary" :loading="loading" @click="onSearch">查询</ElButton>
        <ElButton @click="reset">重置</ElButton>
      </ElFormItem>
    </ElForm>

    <ElTable
      v-loading="loading"
      :data="page.records"
      border
      :default-sort="{ prop: 'createTime', order: 'descending' }"
      @sort-change="onSortChange"
    >
      <ElTableColumn v-if="sortable.has('id')" prop="id" label="ID" width="200" sortable="custom">
        <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
      </ElTableColumn>
      <ElTableColumn prop="username" label="用户名" width="160" sortable="custom" />
      <ElTableColumn prop="realName" label="姓名" width="140" sortable="custom" />
      <ElTableColumn prop="phone" label="手机号" width="160">
        <template #default="{ row }">
          <span class="mono">{{ row.phone || '-' }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="status" label="状态" width="100" sortable="custom">
        <template #default="{ row }">
          <ElTag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="createTime" label="创建时间" width="180" sortable="custom" />
      <ElTableColumn prop="lastLoginAt" label="最后登录" min-width="180" sortable="custom" />
    </ElTable>

    <ElPagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="page.total"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="load"
      @size-change="onSizeChange"
    />

    <p class="tip">
      手机号由后端 <code>@JsonMask</code> 处理后返回，前端拿不到全文；租户隔离、排序白名单均在服务端强制。
    </p>
  </div>
</template>

<style scoped>
.pager {
  margin-top: 12px;
}

.tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
</style>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { fetchUsers } from '@/api'
import type { UserView } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<UserView[]>([])
const total = ref(0)
const filters = reactive({ keyword: '', status: undefined as number | undefined })
const pageQuery = reactive({ pageNum: 1, pageSize: 20 })

async function load() {
  loading.value = true
  try {
    const page = await fetchUsers({
      ...pageQuery,
      keyword: filters.keyword || undefined,
      status: filters.status,
    })
    rows.value = page.records ?? []
    total.value = Number(page.total ?? 0)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pageQuery.pageNum = 1
  load()
}

onMounted(() => {
  if (auth.hasAuthority('system:user:list')) load()
})
</script>

<template>
  <el-card>
    <el-form inline>
      <el-form-item label="关键字">
        <el-input
          v-model="filters.keyword"
          placeholder="用户名 / 姓名"
          clearable
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" clearable placeholder="全部" class="status-select">
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" class="refresh-btn" @click="load">刷新</el-button>
        <el-tag type="info" size="small">共 {{ total }} 个账号</el-tag>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" scrollbar-always-on>
      <el-table-column label="ID" prop="id" width="110" />
      <el-table-column label="用户名" prop="username" width="140" />
      <el-table-column label="姓名" prop="realName" width="120" />
      <el-table-column label="手机号（脱敏）" width="140">
        <template #default="{ row }">{{ row.phone || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">{{ fmtTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有匹配的账号" :image-size="60" />
      </template>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageQuery.pageSize"
      :current-page="pageQuery.pageNum"
      @current-change="(p: number) => { pageQuery.pageNum = p; load() }"
    />
  </el-card>
</template>

<style scoped>
.status-select {
  width: 120px;
}

.refresh-btn {
  margin-left: 8px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>

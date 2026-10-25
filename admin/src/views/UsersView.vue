<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { fetchUsers } from '@/api'
import { usePagedList } from '@/composables/usePagedList'
import PagerBar from '@/components/PagerBar.vue'
import { fmtTime } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const filters = reactive({ keyword: '', status: undefined as number | undefined })

// 筛选条件在闭包里带上，分页与加载态交给 usePagedList
const { rows, total, loading, page, load, search, goTo } = usePagedList(
  (q) =>
    fetchUsers({
      ...q,
      keyword: filters.keyword || undefined,
      status: filters.status,
    }),
  { errorText: '账号加载失败' },
)

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
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" clearable placeholder="全部" class="status-select">
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
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

    <PagerBar
      :total="total"
      :page-size="page.pageSize"
      :page-num="page.pageNum"
      @change="goTo"
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
</style>

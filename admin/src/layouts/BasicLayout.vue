<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Money,
  Monitor,
  Odometer,
  Phone,
  PriceTag,
  Tickets,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

interface MenuItem {
  path: string
  title: string
  authority: string[]
}

const iconMap: Record<string, Component> = {
  '/dashboard': Odometer,
  '/work-orders': Tickets,
  '/pending-calls': Phone,
  '/anomalies': Warning,
  '/funds': Money,
  '/pricing': PriceTag,
  '/ops': Monitor,
  '/users': User,
}

// 菜单显隐按权限点渲染（服务端判定才是准绳，这里只是引导）
const menuItems = computed<MenuItem[]>(() => {
  const items = (router.options.routes.find((r) => r.path === '/')?.children ?? []).map((r) => ({
    path: `/${r.path}`,
    title: String(r.meta?.title ?? r.path),
    authority: (r.meta?.authority as string[] | undefined) ?? [],
  }))
  return items.filter((item) => item.authority.length === 0 || auth.hasAnyAuthority(item.authority))
})

async function onLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="224px" class="aside">
      <div class="brand">
        <div class="brand-name">储物柜运营后台</div>
        <div class="brand-sub">现场、工单、资金一处处置</div>
      </div>
      <el-menu :default-active="route.path" router class="menu">
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon v-if="iconMap[item.path]">
            <component :is="iconMap[item.path]" />
          </el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="title">{{ (route.meta.title as string) ?? '' }}</span>
        </div>
        <div class="header-right">
          <span class="user">{{ auth.account?.username ?? '' }}</span>
          <el-tag v-if="auth.tenantCode" size="small" type="info">
            租户 {{ auth.tenantCode }}
          </el-tag>
          <el-tag v-else-if="auth.account?.tenantId" size="small" type="info">
            租户 #{{ auth.account.tenantId }}
          </el-tag>
          <el-button link type="primary" @click="onLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  border-right: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
}

.brand {
  padding: 18px 20px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
}

.brand-sub {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.menu {
  border-right: none;
  flex: 1;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.main {
  background: var(--el-fill-color-lighter);
  overflow: auto;
}
</style>

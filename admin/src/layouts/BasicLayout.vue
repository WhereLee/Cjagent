<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import { menuItems } from '@/router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

// 菜单在 setup 期算一次：权限来自登录后拉到的 /me，运行期不会变
const menus = computed(() => menuItems())
const accountName = computed(() => auth.account?.username ?? '未登录')
const tenantId = computed(() => auth.account?.tenantId ?? '-')

async function onLogout() {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <ElContainer class="shell">
    <ElAside width="200px">
      <div class="brand">换电柜 SaaS</div>
      <ElMenu :default-active="router.currentRoute.value.path" router>
        <ElMenuItem v-for="menu in menus" :key="menu.path" :index="menu.path">
          {{ menu.title }}
        </ElMenuItem>
      </ElMenu>
    </ElAside>

    <ElContainer>
      <ElHeader class="header">
        <span>租户 {{ tenantId }}</span>
        <span class="account">{{ accountName }}</span>
        <ElButton size="small" text @click="onLogout">退出</ElButton>
      </ElHeader>
      <ElMain>
        <RouterView />
      </ElMain>
    </ElContainer>
  </ElContainer>
</template>

<style scoped>
.shell {
  height: 100%;
}

.brand {
  padding: 16px;
  font-weight: 600;
  color: #fff;
  background: #2b3a4a;
}

.el-aside {
  background: #304156;
}

.el-menu {
  border-right: none;
  background: #304156;
}

:deep(.el-menu-item) {
  color: #bfcbd9;
}

:deep(.el-menu-item.is-active) {
  color: #263445;
  background: #e6e8eb;
}

.header {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: flex-end;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.account {
  font-weight: 600;
}
</style>

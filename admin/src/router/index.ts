import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title: string
    /** 需要的权限码；缺省表示登录即可访问 */
    perm?: string
    /** 登录页/错误页这类不要求登录的路线 */
    publicAccess?: boolean
    /** 是否在侧栏菜单里显示 */
    inMenu?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', publicAccess: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '运行状态', inMenu: true },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UserListView.vue'),
        // 权限码与后端 @PreAuthorize("hasAuthority('system:user:list')") 同一个字符串：
        // 前端隐藏入口只是体验，真正的拒绝仍由服务端返回 40300
        meta: { title: '账号列表', perm: 'system:user:list', inMenu: true },
      },
      {
        path: 'account',
        name: 'account',
        component: () => import('@/views/AccountView.vue'),
        meta: { title: '当前账号', inMenu: true },
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/ForbiddenView.vue'),
        meta: { title: '无访问权限', publicAccess: true },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', publicAccess: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 路由守卫做三件事：未登录跳登录、登录后确保拿到账号信息、无权限跳 403。
 *
 * `loadAccount()` 失败时不吞异常：token 已失效又停在原页会让界面显示成"有名字没数据"，
 * 那种半登录状态最难排查，所以一律清掉重新走登录。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  document.title = `${to.meta.title ?? ''} · 换电柜运营后台`

  if (to.meta.publicAccess) return true

  if (!auth.loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  try {
    await auth.loadAccount()
  } catch {
    auth.clear()
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.perm && !auth.hasAuthority(to.meta.perm)) {
    return { name: 'forbidden' }
  }

  return true
})

/** 侧栏菜单：登录可进的 + 有权限的才列出来 */
export function menuItems() {
  const auth = useAuthStore()
  const root = routes.find((r) => r.path === '/')
  return (root?.children ?? [])
    .filter((child) => child.meta?.inMenu)
    .filter((child) => !child.meta?.perm || auth.hasAuthority(child.meta.perm))
    .map((child) => ({ path: `/${child.path}`, title: child.meta?.title ?? child.path }))
}

export default router

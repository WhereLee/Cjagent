import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { ElMessage } from 'element-plus'

import { ApiError } from '@/api/http'
import { ResultCode } from '@/types/api'
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
        path: 'locker/anomalies',
        name: 'locker-anomalies',
        component: () => import('@/views/AnomalyLedgerView.vue'),
        // 权限码与后端 @PreAuthorize 用同一字符串；菜单里只在有权限时出现，
        // 但“能不能调”仍由服务端 40300 决定（前端隐藏不是门禁）
        meta: { title: '异常格口台账', perm: 'locker:ledger:list', inMenu: true },
      },
      {
        path: 'locker/funds',
        name: 'locker-funds',
        component: () => import('@/views/FundTodoView.vue'),
        // 故意不写 perm：这一页两个 tab 各需一个权限点，挂任一个都会误挡只有另一个权限的人；
        // 可见性由页内 hasAuthority 控制，接口调用仍逐个受服务端校验
        meta: { title: '资金待办', inMenu: true },
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
 * <b>`loadAccount()` 失败必须分因处置，不能一律踢下线。</b>
 * 之前写成 `catch { clear(); 跳登录 }`，于是网络抖动、后端 5xx、超时也会把凭证有数的用户
 * 踢回登录页，表现为“登录明明成功了却又退回来”且无任何线索——这种偶发问题最难查。
 * 只有凭证真的失效（40100）才清除登录态；其余情况保留登录态、告知原因并继续导航（服务端才是门禁）。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  document.title = `${to.meta.title ?? ''} · 暂存柜运营后台`

  if (to.meta.publicAccess) return true

  if (!auth.loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  try {
    await auth.loadAccount()
  } catch (error) {
    if (error instanceof ApiError && error.code === ResultCode.UNAUTHORIZED) {
      auth.clear()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 非凭证问题：不能当成未登录处理，否则就是把用户往登录页里扫
    const reason = error instanceof Error ? error.message : '未知错误'
    console.warn(`加载账号信息失败，保留登录态继续导航：${reason}`)
    if (to.meta.perm) {
      // 拿不到权限就没法判断能不能进，此时宁可拦住并说清原因，也不要让他看到一个半空页面
      ElMessage.error('暂时无法获取权限信息，请稍后重试')
      return { name: 'forbidden' }
    }
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

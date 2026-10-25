import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由与权限点（hasAuthority）挂钩：403 = 当前账号无该权限点。
 * 菜单显隐也按同一份权限点渲染——但服务端判定才是准绳，前端显隐只是引导。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/BasicLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          // 工作台不设权限点：登录即可看；卡片按各自权限点决定是否加载
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrdersView.vue'),
          meta: { title: '订单查询', authority: ['order:list'] },
        },
        {
          path: 'work-orders',
          name: 'work-orders',
          component: () => import('@/views/WorkOrdersView.vue'),
          meta: { title: '工单队列', authority: ['workorder:online:list', 'workorder:field:list'] },
        },
        {
          path: 'pending-calls',
          name: 'pending-calls',
          component: () => import('@/views/PendingCallsView.vue'),
          meta: { title: '待联系客户', authority: ['workorder:online:list'] },
        },
        {
          path: 'anomalies',
          name: 'anomalies',
          component: () => import('@/views/AnomalyLedgerView.vue'),
          meta: { title: '异常台账', authority: ['locker:ledger:list'] },
        },
        {
          // 客户禁用/启用动作挂在资金页的欠费清单行上（后端只有按 ID 的状态接口，没有客户列表）
          path: 'funds',
          name: 'funds',
          component: () => import('@/views/FundsView.vue'),
          meta: { title: '资金运营', authority: ['locker:arrears:list', 'locker:deposit:unrefunded-list'] },
        },
        {
          path: 'pricing',
          name: 'pricing',
          component: () => import('@/views/PricingView.vue'),
          meta: { title: '定价策略', authority: ['pricing:rule:list'] },
        },
        {
          path: 'ops',
          name: 'ops',
          component: () => import('@/views/OpsDiagnoseView.vue'),
          meta: { title: '运维诊断', authority: ['ops:diagnose'] },
        },
        {
          // 死信是“有一堆东西等你处理”，与“生成一份报告给你看”是两件事，所以单独成页
          path: 'dead-letters',
          name: 'dead-letters',
          component: () => import('@/views/DeadLettersView.vue'),
          meta: { title: '死信队列', authority: ['ops:diagnose'] },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { title: '账号列表', authority: ['system:user:list'] },
        },
      ],
    },
    { path: '/403', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true

  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.meLoaded) {
    try {
      await auth.loadMe()
    } catch {
      auth.clearSession()
      return { name: 'login' }
    }
  }

  const required = to.meta.authority as string[] | undefined
  if (required && required.length && !auth.hasAnyAuthority(required)) {
    return { name: 'forbidden' }
  }
  return true
})

export default router

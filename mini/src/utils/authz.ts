import { useRiderStore } from '@/stores/rider'

/**
 * 页面级鉴权。小程序没有 vue-router，也就没有全局守卫，所以拦在两个地方：
 * 页面 onLoad/onShow 这里（体验），以及后端每个接口的 `hasRole('RIDER')`（安全）。
 *
 * @returns 是否可以继续渲染
 */
export async function ensureAuthenticated(): Promise<boolean> {
  const store = useRiderStore()

  if (!store.loggedIn) {
    uni.reLaunch({ url: '/pages/login/index' })
    return false
  }

  try {
    await store.loadProfile()
  } catch {
    // 不重复弹提示：http 层已经处理了"凭证失效→清态回登录页"和错误提示。
    // 这里只负责不再往下渲染，避免出现"有名字没数据"的半登录态
    return false
  }

  return true
}

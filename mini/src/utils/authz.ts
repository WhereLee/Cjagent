import { ApiError } from '@/api/http'
import { ResultCode } from '@/types/api'
import { useCustomerStore } from '@/stores/customer'

/**
 * 页面级鉴权。小程序没有 vue-router，也就没有全局守卫，所以拦在两个地方：
 * 页面 onLoad/onShow 这里（体验），以及后端每个接口的 `hasRole('CUSTOMER')`（安全）。
 *
 * <p>注意本函数依赖 `isLoggedIn()` 是**函数**而非缓存的 computed（见 stores/customer.ts 的注释）：
 * 曾经写 `loggedIn` computed 缓存了 Storage 读取结果，退出登录后仍为 true，
 * 导致"只改地址栏 hash 直达受限页"这条路径完全不拦（浏览器实测 2/2 复现）。
 *
 * @returns 是否可以继续渲染
 */
export async function ensureAuthenticated(): Promise<boolean> {
  const store = useCustomerStore()

  if (!store.isLoggedIn()) {
    backToLogin()
    return false
  }

  try {
    await store.loadProfile()
  } catch (error) {
    // 凭证类失败：清掉本地并回登录页
    if (error instanceof ApiError && error.code === ResultCode.UNAUTHORIZED) {
      backToLogin()
    }
    // 其余失败（网络抖动、后端 5xx）不登出、不假装未登录：只停止渲染，
    // 提示已由请求层按错误码弹出过
    return false
  }

  return true
}

/**
 * 回登录页。
 *
 * <p>`animationDuration: 0` 不是为「更快」：上一跳的页面过渡尚未完成时，
 * uni-app 的 H5 路由会**丢弃本次 reLaunch**（实测：隐藏标签页里 rAF 被冻结、
 * 或 300ms 内连发导航），结果是“地址栏停在受限页 + 整页空白”。
 * 去掉动画窗口就绕开了这个竞态；同时页面自身还有兜底组件，不把正确性
 * 完全压在导航成功上。
 */
function backToLogin(): void {
  uni.reLaunch({ url: '/pages/login/index', animationDuration: 0 })
}

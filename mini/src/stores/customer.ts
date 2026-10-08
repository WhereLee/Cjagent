import { defineStore } from 'pinia'
import { ref } from 'vue'

import { fetchProfile, loginApi, logoutApi, type CustomerProfile } from '@/api/auth'
import { ApiError, clearTokensAndRelaunch, readAccessToken, readRefreshToken, saveTokens } from '@/api/http'
import { ResultCode } from '@/types/api'

const DEV_CODE_KEY = 'cust.devCode'

/**
 * 取登录凭证 code。
 *
 * <p>H5 没有 `wx.login`，走 mock 通道：生成一个<b>稳定</b>的 code 存本地，
 * 后端 `MockMiniAppClient` 把它直接当 openid 用。不稳定就等于每登录一次
 * 注册一个新客户，测试数据会瞬间脏掉（这条是"同设备同一身份"的关键）。
 *
 * <p>编译到微信小程序时改用 `uni.login` 的真实 code；mock 分支由后端
 * `mini.mock-login` + `@Profile("!prod")` 两层锁住。
 */
function resolveLoginCode(): Promise<string> {
  // #ifdef MP-WEIXIN
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res) => (res.code ? resolve(res.code) : reject(new Error('uni.login 未返回 code'))),
      fail: (err) => reject(new Error(err.errMsg || '微信登录失败')),
    })
  })
  // #endif

  // #ifndef MP-WEIXIN
  let code = (uni.getStorageSync(DEV_CODE_KEY) as string) || ''
  if (!code) {
    code = `h5-cust-${Date.now().toString(36)}`
    uni.setStorageSync(DEV_CODE_KEY, code)
  }
  return Promise.resolve(code)
  // #endif
}

/**
 * 登录态。
 *
 * <p>判定一律走函数（`isLoggedIn()`），<b>不要包成 computed</b>：computed 读 Storage
 * 这种非响应式源会只求值一次、永不失效，退出登录后仍返回 true（实测导致
 * "仅改 hash 直达受限页"完全不拦）。
 *
 * <p>安全上的已知取舍：两个 token 都存在本地存储，XSS 一旦发生即可被读走。
 * 企业标准是 refresh 放 httpOnly + SameSite Cookie、access 只存内存，
 * 已登记在 docs/架构约定.md §7，接真实用户数据前必须换。
 */
export const useCustomerStore = defineStore('customer', () => {
  const profile = ref<CustomerProfile | null>(null)

  function isLoggedIn(): boolean {
    return Boolean(readAccessToken())
  }

  /** 本地缓存里没有就回源。无 token 一定抛 40100：抛错类型不对，守卫就无法分因处置（只会停在空白页）。 */
  async function loadProfile(force = false): Promise<CustomerProfile | null> {
    if (profile.value && !force) return profile.value
    if (!readAccessToken()) {
      throw new ApiError(ResultCode.UNAUTHORIZED, '未登录或登录状态已过期')
    }
    profile.value = await fetchProfile()
    return profile.value
  }

  async function login(tenantCode?: string): Promise<boolean> {
    const code = await resolveLoginCode()
    const result = await loginApi(code, tenantCode)
    saveTokens(result.accessToken, result.refreshToken)
    await loadProfile(true)
    return result.newRegister
  }

  async function logout(): Promise<void> {
    const refresh = readRefreshToken()
    try {
      if (refresh) await logoutApi(refresh)
    } catch {
      // 后端可能已判定凭证失效；本地照样要清干净，否则出现"看着退出了，token 还在"
    } finally {
      profile.value = null
      clearTokensAndRelaunch()
    }
  }

  return { profile, isLoggedIn, loadProfile, login, logout }
})

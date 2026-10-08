import { defineStore } from 'pinia'
import { ref } from 'vue'

import { fetchProfile, loginApi, logoutApi, type RiderProfile } from '@/api/auth'
import { ApiError, clearTokensAndRelaunch, readAccessToken, readRefreshToken, saveTokens } from '@/api/http'
import { ResultCode } from '@/types/api'

const DEV_CODE_KEY = 'rider.devCode'

/**
 * 取登录凭证 code。
 *
 * <p>H5 没有 `wx.login`，所以走 mock 通道：生成一个<b>稳定</b>的 code 存本地，
 * 后端 `MockMiniAppClient` 会把 code 直接当 openId 用——不稳定就等于每登录一次
 * 就注册一个新骑手，测试数据会瞬间脏掉。
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
    code = `h5-rider-${Date.now().toString(36)}`
    uni.setStorageSync(DEV_CODE_KEY, code)
  }
  return Promise.resolve(code)
  // #endif
}

export const useRiderStore = defineStore('rider', () => {
  const profile = ref<RiderProfile | null>(null)

  /**
   * 登录判定必须是**函数**而不是 computed。
   *
   * 上一版写的 `computed(() => Boolean(readAccessToken()))`：它依赖的是 uni Storage，
   * 不是响应式源，于是 computed 求值一次就永不失效——退出登录清完 Storage 它仍回 true，
   * 仅改 hash 直达受限页的拦截因此失效（浏览器实测重现，复现率 2/2）。
   */
  function isLoggedIn(): boolean {
    return Boolean(readAccessToken())
  }

  /** 本地缓存里没有就回源。没 token 一定抛错，**不得静默返回 null**：否则调用方会把“未登录”当成“已加载完成”。 */
  async function loadProfile(force = false): Promise<RiderProfile | null> {
    if (profile.value && !force) return profile.value
    if (!readAccessToken()) {
      throw new ApiError(ResultCode.UNAUTHORIZED, '未登录或登录状态已过期')
    }
    profile.value = await fetchProfile()
    return profile.value
  }

  async function login(tenantCode?: string): Promise<boolean> {
    const code = await resolveLoginCode()
    const result = await loginApi(code, tenantCode || undefined)
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

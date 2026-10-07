import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  fetchMe,
  login as loginApi,
  logoutApi,
  type CurrentAccount,
  type LoginPayload,
} from '@/api/auth'

const ACCESS_KEY = 'cab.at'
const REFRESH_KEY = 'cab.rt'

/**
 * 登录态。
 *
 * <b>安全上的已知取舍</b>：两个 token 都放 localStorage，XSS 一旦发生即可被读走。
 * 企业标准做法是 refresh token 放 httpOnly + SameSite=Strict 的 Cookie、access token 只存内存
 * （刷新靠 Cookie 静默续期）。底座阶段先用 localStorage 换取实现简单，
 * <b>接真实用户数据前必须换掉</b>，已登记在 docs/架构约定.md §7。
 */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(localStorage.getItem(ACCESS_KEY))
  const refreshToken = ref<string | null>(localStorage.getItem(REFRESH_KEY))
  const account = ref<CurrentAccount | null>(null)

  const loggedIn = computed(() => Boolean(accessToken.value))
  const authoritySet = computed(() => new Set(account.value?.authorities ?? []))

  function hasAuthority(code: string): boolean {
    return authoritySet.value.has(code)
  }

  /** 与后端 hasRole 对齐：角色串在装载时就带了 ROLE_ 前缀 */
  function hasRole(code: string): boolean {
    return authoritySet.value.has(`ROLE_${code}`)
  }

  function setTokens(access: string, refresh: string): void {
    accessToken.value = access
    refreshToken.value = refresh
    localStorage.setItem(ACCESS_KEY, access)
    localStorage.setItem(REFRESH_KEY, refresh)
  }

  function clear(): void {
    accessToken.value = null
    refreshToken.value = null
    account.value = null
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  }

  async function login(payload: LoginPayload): Promise<void> {
    const pair = await loginApi(payload)
    setTokens(pair.accessToken, pair.refreshToken)
    // 登录后立刻拉一次账号信息：菜单与按钮的渲染依赖它，且它能第一时间暴露凭证是否真的可用
    await loadAccount()
  }

  /** 已登录则拉账号信息；失败（凭证失效）由调用方决定是否登出，刷新逻辑在 http 层。 */
  async function loadAccount(force = false): Promise<CurrentAccount | null> {
    if (!accessToken.value) return null
    if (account.value && !force) return account.value
    account.value = await fetchMe()
    return account.value
  }

  async function logout(): Promise<void> {
    try {
      await logoutApi(refreshToken.value)
    } catch {
      // 后端已经判定凭证失效等情况，本地照样要清干净，否则会出现"看着退出了其实 token 还在"
    } finally {
      clear()
    }
  }

  return {
    accessToken,
    refreshToken,
    account,
    loggedIn,
    setTokens,
    clear,
    login,
    loadAccount,
    logout,
    hasAuthority,
    hasRole,
  }
})

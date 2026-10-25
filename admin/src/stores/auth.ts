import { defineStore } from 'pinia'
import { adminLogin, adminLogout, adminMe, adminRefresh } from '@/api'
import type { CurrentAccountView } from '@/api/schema-admin'

const STORAGE_KEY = 'cabinet-admin-session'

interface Session {
  accessToken: string
  refreshToken: string
  tenantCode?: string
}

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: loadSession()?.accessToken ?? '',
    refreshToken: loadSession()?.refreshToken ?? '',
    // 顶栏展示的是人登录时填的租户编码；后端 me 只回 tenantId，不回编码
    tenantCode: loadSession()?.tenantCode ?? '',
    account: null as CurrentAccountView | null,
    meLoaded: false,
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.accessToken),
    authorities: (state) => new Set(state.account?.authorities ?? []),
  },
  actions: {
    hasAuthority(point: string) {
      return this.authorities.has(point)
    },
    hasAnyAuthority(points: string[]) {
      return points.some((p) => this.authorities.has(p))
    },
    saveSession(session: Session) {
      this.accessToken = session.accessToken
      this.refreshToken = session.refreshToken
      this.tenantCode = session.tenantCode ?? this.tenantCode
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          accessToken: this.accessToken,
          refreshToken: this.refreshToken,
          tenantCode: this.tenantCode,
        }),
      )
    },
    clearSession() {
      this.accessToken = ''
      this.refreshToken = ''
      this.tenantCode = ''
      this.account = null
      this.meLoaded = false
      localStorage.removeItem(STORAGE_KEY)
    },
    async login(tenantCode: string, username: string, password: string) {
      const token = await adminLogin({ tenantCode, username, password })
      this.saveSession({
        accessToken: token.accessToken ?? '',
        refreshToken: token.refreshToken ?? '',
        tenantCode,
      })
      await this.loadMe()
    },
    async logout() {
      try {
        if (this.refreshToken) {
          await adminLogout({ refreshToken: this.refreshToken })
        }
      } finally {
        this.clearSession()
      }
    },
    /** 一次性 refreshToken：用过即废，服务端会发新的对 */
    async refreshAccessToken(): Promise<string | null> {
      if (!this.refreshToken) return null
      const token = await adminRefresh({ refreshToken: this.refreshToken })
      this.saveSession({ accessToken: token.accessToken ?? '', refreshToken: token.refreshToken ?? '' })
      return this.accessToken
    },
    async loadMe() {
      this.account = await adminMe()
      this.meLoaded = true
    },
  },
})

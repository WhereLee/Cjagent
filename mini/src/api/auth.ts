import { request } from '@/api/http'

/** 登录返回：凭证 + 身份 + 是否新注册（newRegister 只影响引导流程，不参与任何权限判定）。 */
export interface RiderLoginResult {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  refreshExpiresIn: number
  riderId: string
  tenantId: string
  newRegister: boolean
}

/**
 * 当前骑手信息。字段与后端 `RiderView` 对应，**不含 openId**：
 * 那是服务端侧身份标识，客户端拿到没有用处，还多一个泄露面。
 */
export interface RiderProfile {
  riderId: string
  tenantId: string
  nickname?: string
  /** 后端已脱敏 */
  phone?: string
  status: number
  registerTime?: string
  lastLoginAt?: string
}

export function loginApi(code: string, tenantCode?: string) {
  return request<RiderLoginResult>({
    url: '/api/mini/auth/login',
    method: 'POST',
    data: { code, tenantCode },
    anonymous: true,
  })
}

export function fetchProfile() {
  return request<RiderProfile>({ url: '/api/mini/auth/me' })
}

export function logoutApi(refreshToken: string) {
  return request<null>({
    url: '/api/mini/auth/logout',
    method: 'POST',
    data: { refreshToken },
    // 这里必须带 access token：后端从凭证 principal 里取身份，“logout 是未登录接口”是错的。
    // 它已在 http.ts 的 AUTH_ENDPOINTS 里，所以 401 时不会去触发刷新，只会静默失败。
    silentError: true,
  })
}

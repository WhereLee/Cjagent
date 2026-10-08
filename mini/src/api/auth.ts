import { request } from '@/api/http'

/** 登录返回：凭证 + 身份 + 是否新注册（newRegister 只影响引导流程，不参与权限判定）。 */
export interface CustomerLoginResult {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  refreshExpiresIn: number
  customerId: string
  tenantId: string
  newRegister: boolean
}

/**
 * 当前客户信息。字段与后端 `CustomerView` 对应，**不含 openId**：
 * 那是服务端侧身份标识，客户端拿到没有用处，还多一个泄露面。
 */
export interface CustomerProfile {
  customerId: string
  tenantId: string
  nickname?: string
  /** 后端已脱敏 */
  phone?: string
  status: number
  registerTime?: string
  lastLoginAt?: string
}

export function loginApi(code: string, tenantCode?: string) {
  return request<CustomerLoginResult>({
    url: '/api/mini/auth/login',
    method: 'POST',
    data: { code, tenantCode },
    anonymous: true,
  })
}

export function fetchProfile() {
  return request<CustomerProfile>({ url: '/api/mini/auth/me' })
}

export function logoutApi(refreshToken: string) {
  return request<null>({
    url: '/api/mini/auth/logout',
    method: 'POST',
    data: { refreshToken },
    // 必须带 access token：后端从凭证 principal 取身份
    silentError: true,
  })
}

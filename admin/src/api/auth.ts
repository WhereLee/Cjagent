import { request } from '@/api/http'

export interface TokenPair {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  refreshExpiresIn: number
}

/**
 * 当前账号信息。
 *
 * 注意 id / tenantId 是 **string**：后端把雪花 ID 序列化成字符串（19 位超 JS 安全整数），
 * 前端一旦按 number 用就会静默丢精度。类型层面写死字符串是防这件事的第一道。
 */
export interface CurrentAccount {
  userId: string
  username: string
  tenantId: string
  end: string
  authorities: string[]
}

export interface LoginPayload {
  tenantCode: string
  username: string
  password: string
}

export function login(payload: LoginPayload) {
  return request<TokenPair>({ url: '/api/admin/auth/login', method: 'post', data: payload })
}

export function fetchMe() {
  return request<CurrentAccount>({ url: '/api/admin/auth/me', method: 'get' })
}

export function logoutApi(refreshToken: string | null) {
  return request<null>({
    url: '/api/admin/auth/logout',
    method: 'post',
    data: refreshToken ? { refreshToken } : undefined,
    silentError: true,
  })
}

/**
 * 跨端请求层（uni.request 封装，H5 与微信小程序通用）。
 *
 * 三条与后端契约对齐的硬规则：
 * 1. code !== 0 即失败——HTTP 状态可能仍是 200（1xxxx 码族），不能只看状态码。
 * 2. message 是后端给的中文文案，直接展示，不自己拼。
 * 3. ID 一律字符串（雪花 ID 超出 JS 安全整数），禁止 parseInt/Number 还原。
 */
import { getToken, setToken, clearToken, getRefreshToken } from '@/stores/auth'

export interface REnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp?: string
}

export class ApiError extends Error {
  readonly code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export interface RequestConfig {
  url: string
  method?: 'GET' | 'POST'
  data?: object
  params?: Record<string, string | number | boolean | undefined>
  /** 匿名接口（凭码取件）不带 Authorization */
  anonymous?: boolean
}

function buildUrl(url: string, params?: RequestConfig['params']): string {
  if (!params) return url
  const pairs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return pairs.length ? `${url}?${pairs.join('&')}` : url
}

/**
 * 技术异常不进用户视野：后端 dev 的 echo-detail 会把 NPE 原文带进 message，
 * 直接展示等于把堆栈扔给用户。业务文案（中文、可行动）原样保留。
 */
function safeMessage(message: string | undefined): string {
  if (!message) return ''
  return /Exception|NullPointer|at com\.|SQLException/i.test(message)
    ? '服务暂时不可用，请稍后重试'
    : message
}

function rawRequest<T>(config: RequestConfig, token: string): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: buildUrl(config.url, config.params),
      method: config.method ?? 'GET',
      data: config.data,
      timeout: 30000,
      header: token && !config.anonymous ? { Authorization: `Bearer ${token}` } : {},
      success: (res) => {
        const status = res.statusCode ?? 200
        const body = res.data as REnvelope<T> | undefined
        // 1xxxx 码族：HTTP 200 但业务失败——判定看 code，不看状态码
        if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
          reject(new ApiError(body.code, safeMessage(body.message)))
          return
        }
        if (status === 401) {
          reject(new ApiError(40100, body?.message || '登录已过期，请重新登录'))
          return
        }
        if (status >= 400) {
          // 网关 / CORS 层拒绝时 body 是纯文本，没有后端的中文 message
          const raw = typeof res.data === 'string' ? res.data : ''
          const text =
            body?.message ||
            (raw.includes('CORS')
              ? '开发环境跨域被拒绝：请检查后端 CORS 白名单'
              : `请求失败（HTTP ${status}）`)
          reject(new ApiError(status, safeMessage(text)))
          return
        }
        resolve(body ? body.data : (res.data as T))
      },
      fail: (err) => {
        reject(new ApiError(-1, err.errMsg || '网络异常，请检查网络后重试'))
      },
    })
  })
}

/** 401 后的 refresh 单飞：并发请求共享一次刷新（refreshToken 一次性，用过即废） */
let refreshInFlight: Promise<string | null> | null = null

function refreshOnce(): Promise<string | null> {
  if (!refreshInFlight) {
    const refreshToken = getRefreshToken()
    if (!refreshToken) return Promise.resolve(null)
    refreshInFlight = new Promise<string | null>((resolve) => {
      uni.request({
        url: '/api/mini/auth/refresh',
        method: 'POST',
        data: { refreshToken },
        success: (res) => {
          const body = res.data as REnvelope<{ accessToken?: string; refreshToken?: string }>
          if (body?.code === 0 && body.data?.accessToken) {
            setToken(body.data.accessToken, body.data.refreshToken ?? '')
            resolve(body.data.accessToken)
          } else {
            resolve(null)
          }
        },
        fail: () => resolve(null),
      })
    }).finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

/** 401 且刷新失败：清会话并回登录页（只做一次，避免并发请求同时跳转） */
let redirectingToLogin = false
function redirectToLogin(): void {
  if (redirectingToLogin) return
  redirectingToLogin = true
  uni.reLaunch({
    url: '/pages/login/index?expired=1',
    complete: () => {
      setTimeout(() => {
        redirectingToLogin = false
      }, 800)
    },
  })
}

export async function request<T>(config: RequestConfig): Promise<T> {
  const token = config.anonymous ? '' : getToken()
  try {
    return await rawRequest<T>(config, token)
  } catch (e) {
    // 401（凭证过期/缺失）且不是认证类接口：刷新一次再试，仍失败清会话回登录页
    if (e instanceof ApiError && e.code === 40100 && !config.anonymous && !config.url.includes('/auth/')) {
      const newToken = await refreshOnce()
      if (newToken) {
        return rawRequest<T>(config, newToken)
      }
      clearToken()
      redirectToLogin()
    }
    throw e
  }
}

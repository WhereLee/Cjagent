import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

/** 后端统一响应包（R<T>）：code=0 成功；非 0 时 message 可直接展示给用户 */
export interface REnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp?: string
}

/** 业务失败：HTTP 可能是 200（1xxxx 码族），判定失败看 code 而不是 HTTP 状态 */
export class ApiError extends Error {
  readonly code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

const http = axios.create({
  // 同源相对路径：dev 由 vite proxy 转发到本地后端，不依赖后端 CORS 配置
  baseURL: '',
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

/** 401 后的 refresh 单飞：并发请求共享同一次刷新，避免互相作废一次性 refreshToken */
let refreshInFlight: Promise<string | null> | null = null

async function refreshOnce(): Promise<string | null> {
  if (!refreshInFlight) {
    const auth = useAuthStore()
    refreshInFlight = auth
      .refreshAccessToken()
      .catch(() => null)
      .finally(() => {
        refreshInFlight = null
      })
  }
  return refreshInFlight
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as REnvelope<unknown>
    // 1xxxx 码族：HTTP 200 但业务失败——必须看 code
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new ApiError(body.code, body.message))
    }
    return response
  },
  async (error: AxiosError<REnvelope<unknown>>) => {
    // 用户主动取消（慢接口）：原样抛出，不包成业务错误
    if (axios.isCancel(error)) {
      return Promise.reject(error)
    }
    const original = error.config as (AxiosRequestConfig & { _retried?: boolean }) | undefined
    const status = error.response?.status
    const body = error.response?.data

    // 401：尝试刷新一次（一次性 refreshToken，用过即废），仍失败回登录页
    if (status === 401 && original && !original._retried && !original.url?.includes('/auth/')) {
      original._retried = true
      const newToken = await refreshOnce()
      if (newToken) {
        original.headers = { ...original.headers, Authorization: `Bearer ${newToken}` }
        return http.request(original)
      }
      const auth = useAuthStore()
      auth.clearSession()
      await router.push('/login')
      return Promise.reject(new ApiError(40100, body?.message ?? '登录已过期，请重新登录'))
    }

    const code = body?.code ?? status ?? -1
    // 后端有中文 message 就用它；技术异常原文（dev echo-detail 会带 NPE）不展示给用户
    let message = body?.message
    if (message && /Exception|NullPointer|at com\.|SQLException/i.test(message)) {
      message = '服务暂时不可用，请稍后重试'
    }
    if (!message) {
      const raw = error.response?.data as unknown
      if (typeof raw === 'string' && raw.includes('CORS')) {
        message = '开发环境跨域被拒绝：请检查后端 CORS 白名单'
      } else if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
        message = '请求超时，请稍后重试'
      } else if (!error.response) {
        message = '网络异常，请确认后端服务已启动'
      } else {
        message = `请求失败（HTTP ${status ?? '未知'}）`
      }
    }
    return Promise.reject(new ApiError(code, message))
  },
)

/**
 * 发起请求并解包 data。
 * 失败抛 ApiError：message 是后端给的中文文案，直接展示，不自己拼。
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<REnvelope<T>>(config)
  return response.data.data
}

export default http

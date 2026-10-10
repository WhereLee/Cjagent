import { ResultCode, type ApiResult } from '@/types/api'

/**
 * 用户端请求层。与 admin 的 http.ts 语义一致（解 R、按 code 分派、401 刷新去重、
 * 认证接口不参与刷新），但底下换成 `uni.request`——原因不是“顺手写个新的”：
 *
 * - 小程序端没有 XHR，axios 跑不起来；
 * - `uni.request` **业务失败不抛异常**，非 2xx 也照样走 success 回调，
 *   所以“HTTP 状态”与“业务 code”必须在这一层分开处理，不能像 axios 那样靠 catch 兜；
 * - 没有 params 序列化，GET 的查询串要自己拼（undefined/空值必须跳过）。
 *
 * <p>两份请求层各写一份是已登记的简化（docs/架构约定.md §7）：接口数量上来后
 * 应抽成共享核心包 + 平台适配器，否则错误码与刷新语义会漂。
 */

const ACCESS_KEY = 'cust.at'
const REFRESH_KEY = 'cust.rt'

const baseURL = import.meta.env.VITE_API_BASE ?? 'http://127.0.0.1:8080'

export class ApiError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

export interface RequestOptions {
  url: string
  /**
   * 只有这四个：`uni.request`（小程序侧）的 method 枚举**不含 PATCH**，
   * 现在改后端容易，等接口发出去再改就是两边兼容矩阵。已写入 docs/架构约定.md。
   */
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** 请求体（POST/PUT） */
  data?: Record<string, unknown>
  /** 查询串（GET），值为 undefined / 空串会被跳过 */
  query?: Record<string, unknown>
  /** 页面自己处理错误时置 true，拦截提示就不弹（避免同一个失败弹两遍） */
  silentError?: boolean
  /** 不需要带凭证的接口（登录本身），默认都要带 */
  anonymous?: boolean
}

interface RawResult {
  statusCode: number
  body: unknown
  failMessage?: string
}

function call(options: RequestOptions): Promise<RawResult> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: baseURL + options.url + buildQuery(options.query),
      method: (options.method ?? 'GET') as UniApp.RequestOptions['method'],
      data: options.data as UniApp.RequestOptions['data'],
      timeout: 15000,
      header: buildHeader(options),
      success: (res) => resolve({ statusCode: res.statusCode, body: res.data }),
      fail: (err) => reject(new ApiError(-1, err.errMsg || '网络异常')),
    })
  })
}

function buildHeader(options: RequestOptions): Record<string, string> {
  const header: Record<string, string> = { 'Content-Type': 'application/json' }
  if (!options.anonymous) {
    const token = uni.getStorageSync(ACCESS_KEY) as string
    if (token) header.Authorization = `Bearer ${token}`
  }
  return header
}

function buildQuery(query?: Record<string, unknown>): string {
  if (!query) return ''
  const parts: string[] = []
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

export function readAccessToken(): string {
  return (uni.getStorageSync(ACCESS_KEY) as string) || ''
}

export function readRefreshToken(): string {
  return (uni.getStorageSync(REFRESH_KEY) as string) || ''
}

export function saveTokens(accessToken: string, refreshToken: string): void {
  uni.setStorageSync(ACCESS_KEY, accessToken)
  uni.setStorageSync(REFRESH_KEY, refreshToken)
}

/**
 * 清登录态并回登录页。
 * 用 reLaunch 而不是 switchTab/navigateTo：凭证失效时页面栈里可能全是需要登录的页，
 * 留着它们用户按返回又会撞一次 401。
 */
export function clearTokensAndRelaunch(): void {
  uni.removeStorageSync(ACCESS_KEY)
  uni.removeStorageSync(REFRESH_KEY)
  // animationDuration: 0：避开“上一跳过渡未完成时 reLaunch 被丢弃”的竞态（见 utils/authz.ts 同处说明）
  uni.reLaunch({ url: '/pages/login/index', animationDuration: 0 })
}

/** 不参与"401 自动刷新"的接口，见下方 send() 里的说明。 */
const AUTH_ENDPOINTS = ['/api/mini/auth/login', '/api/mini/auth/refresh', '/api/mini/auth/logout']

function isAuthEndpoint(url: string): boolean {
  return AUTH_ENDPOINTS.some((endpoint) => url.startsWith(endpoint))
}

/**
 * 并发 401 只刷新一次：refresh 是一次性的（后端轮换后旧的立刻作废），
 * 不去重就是"第一个刷新成功、其余全部被踢下线"。
 */
let refreshing: Promise<boolean> | null = null

function refreshOnce(): Promise<boolean> {
  if (refreshing) return refreshing

  refreshing = (async () => {
    const token = readRefreshToken()
    if (!token) return false
    try {
      const raw = await call({
        url: '/api/mini/auth/refresh',
        method: 'POST',
        data: { refreshToken: token },
        anonymous: true,
      })
      const body = raw.body as ApiResult<{ accessToken: string; refreshToken: string }>
      if (body.code !== ResultCode.SUCCESS || !body.data) return false
      saveTokens(body.data.accessToken, body.data.refreshToken)
      return true
    } catch {
      return false
    }
  })().finally(() => {
    refreshing = null
  })

  return refreshing
}

function messageFor(code: number, backendMessage: string): string {
  switch (code) {
    case ResultCode.PARAM_INVALID:
      return backendMessage || '参数有误'
    case ResultCode.UNAUTHORIZED:
      return backendMessage || '登录已过期，请重新登录'
    case ResultCode.TENANT_INVALID:
      return backendMessage || '当前租户不可用，请联系运营商'
    case ResultCode.FORBIDDEN:
      return backendMessage || '没有该操作的权限'
    case ResultCode.IDEMPOTENT_REJECT:
      return backendMessage || '请勿重复提交'
    case ResultCode.TOO_MANY_REQUESTS:
      return '操作过于频繁，请稍后再试'
    case ResultCode.SYSTEM_ERROR:
    case ResultCode.MIDDLEWARE_UNAVAILABLE:
      return '系统繁忙，请稍后重试'
    default:
      return backendMessage || '操作失败'
  }
}

function notify(code: number, message: string, traceId?: string): void {
  // 服务端错误带上 traceId：客户在户外报障时，这一串字符是唯一能对齐日志的东西
  const title = code >= ResultCode.SYSTEM_ERROR && traceId ? `${message}（${traceId.slice(0, 8)}）` : message
  uni.showToast({ title, icon: 'none', duration: 2500 })
}

function unwrap<T>(raw: RawResult): T {
  const body = raw.body as ApiResult<T> | undefined
  if (!body || typeof body.code !== 'number') {
    // 拿不到统一结构：网关/静态资源误配、或后端未启动返回了别的东西
    throw new ApiError(ResultCode.SYSTEM_ERROR, `服务响应异常（HTTP ${raw.statusCode}）`)
  }
  if (body.code !== ResultCode.SUCCESS) {
    throw new ApiError(body.code, body.message, body.traceId)
  }
  return body.data as T
}

/** 统一出口：页面拿到的就是 data 本体，失败一律以 ApiError 抛出。 */
export async function request<T>(options: RequestOptions): Promise<T> {
  let raw: RawResult
  try {
    raw = await call(options)
  } catch (error) {
    // 只有网络层失败会到这里（uni.request 的 fail）
    const message = error instanceof ApiError ? error.message : '网络异常，请检查网络'
    if (!options.silentError) uni.showToast({ title: message, icon: 'none' })
    throw error
  }

  try {
    return unwrap<T>(raw)
  } catch (error) {
    if (!(error instanceof ApiError)) throw error

    const retryable = error.code === ResultCode.UNAUTHORIZED && !isAuthEndpoint(options.url) && !options.anonymous
    if (retryable) {
      const ok = await refreshOnce()
      if (ok) {
        // 重试一次；再失败就不再刷新，避免刷新与重试互相触发
        const second = await call({ ...options, anonymous: false })
        try {
          return unwrap<T>(second)
        } catch (retryError) {
          if (retryError instanceof ApiError && retryError.code === ResultCode.UNAUTHORIZED) {
            clearTokensAndRelaunch()
          }
          throw retryError
        }
      }
      clearTokensAndRelaunch()
      if (!options.silentError) notify(error.code, '登录已过期，请重新登录', error.traceId)
      throw error
    }

    if (!options.silentError) notify(error.code, messageFor(error.code, error.message), error.traceId)
    throw error
  }
}

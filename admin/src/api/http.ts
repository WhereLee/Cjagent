import axios, { type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
// 按需引入下，模板里的组件由解析器自动带样式，但直接 import 的 API（ElMessage）不经过它，
// 样式必须自引，否则提示条会没样式地贴在页面顶部、很难看也没人报 bug
import 'element-plus/es/components/message/style/css'

import { ResultCode, type ApiResult } from '@/types/api'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 页面自己处理错误时置 true，拦截器就不再弹提示（避免同一次失败弹两次） */
    silentError?: boolean
    /** 内部使用：401 后已经重试过一次，防止刷新失败时死循环 */
    _retriedAfterRefresh?: boolean
  }
}

/** 统一的后端业务异常。页面可以 catch 它并按 code 做特殊处理。 */
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

/**
 * http 层与登录态之间的桥。
 *
 * 不直接 import pinia store：那样会造成 http → store → http 的循环依赖，
 * 打包后表现为"某些字段偶发 undefined"这类极难查的问题。装配在 main.ts 完成。
 */
export interface SessionBridge {
  accessToken(): string | null
  refreshToken(): string | null
  /** 刷新成功后写回新的一对 */
  applyTokens(accessToken: string, refreshToken: string): void
  /** 彻底失效：清登录态并跳登录页 */
  forceLogout(): void
}

let session: SessionBridge | null = null

export function bindSession(bridge: SessionBridge): void {
  session = bridge
}

const baseURL = import.meta.env.VITE_API_BASE ?? ''

const http: AxiosInstance = axios.create({ baseURL, timeout: 15000 })

/** 裸实例：只用于刷新接口，不挂拦截器，避免刷新失败时又被 401 拦截器再次触发刷新。 */
const rawHttp: AxiosInstance = axios.create({ baseURL, timeout: 10000 })

/**
 * 并发 401 只刷新一次。
 *
 * 首页往往同时发 3~5 个请求，token 过期时会一起 401。没有这个 promise 复用的话，
 * 每个请求都会各自刷一次，而 refresh 是**一次性**的（后端轮换后旧的立刻作废），
 * 结果就是第一个刷新成功、其余全部失败并被踢到登录页。
 */
let refreshing: Promise<boolean> | null = null

function refreshOnce(): Promise<boolean> {
  if (!session) return Promise.resolve(false)
  if (refreshing) return refreshing

  refreshing = (async () => {
    const rt = session?.refreshToken()
    if (!rt) return false
    try {
      const resp = await rawHttp.post<ApiResult<{ accessToken: string; refreshToken: string }>>(
        '/api/admin/auth/refresh',
        { refreshToken: rt },
      )
      const body = resp.data
      if (body.code !== ResultCode.SUCCESS || !body.data) return false
      session?.applyTokens(body.data.accessToken, body.data.refreshToken)
      return true
    } catch {
      return false
    }
  })().finally(() => {
    refreshing = null
  })

  return refreshing
}

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = session?.accessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

/**
 * 认证类接口不参与"401 自动刷新"。
 *
 * 否则登录失败（后端返回 40100 账号或口令不正确）会被当成凭证过期去刷新，
 * 用户看到的是“登录已过期，请重新登录”，而真实原因其实是口令错 ——
 * 这类文案错位会让人反复重试、完全查不到方向。
 */
const AUTH_ENDPOINTS = ['/api/admin/auth/login', '/api/admin/auth/refresh', '/api/admin/auth/logout']

function isAuthEndpoint(url: string | undefined): boolean {
  if (!url) return false
  return AUTH_ENDPOINTS.some((endpoint) => url.startsWith(endpoint))
}

/** 按 code 决定给用户的文案。分得细是因为这三种处理动作完全不同。 */
function messageFor(code: number, backendMessage: string): string {
  switch (code) {
    case ResultCode.PARAM_INVALID:
      return backendMessage || '参数有误'
    case ResultCode.TENANT_INVALID:
      // 不是"重新登录"能解决的，提示语必须区别开，否则用户反复登录仍失败
      return backendMessage || '当前租户不可用，请联系管理员'
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

/**
 * 失败提醒：按错误码分文案，并避开同一次失败弹两次。
 *
 * <b>为什么需要一个函数而不是只在拒绝分支里弹</b>：后端业务拒绝是
 * `HTTP 200 + code != 0`（全局异常处理器按码族决定状态，很多业错误就是 200），
 * 它在**成功分支**里被抛出，不经过拒绝分支。之前只弹拒绝分支时，
 * “柜门未关不给清柜”“证据不足不能免除”这类可预期拒绝在页面上**什幺反馈都没有**：
 * 弹窗关掉、数据没变、用户以为了不起作用，只能反复点（实测就是这样）。
 * 业务拒绝恰恰是最需要把服务端原因原文给用户看的一类。
 */
function notifyFailure(code: number, backendMessage: string, silent: boolean): void {
  if (!silent) {
    ElMessage.error(messageFor(code, backendMessage))
  }
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown> | undefined
    // 非 JSON（如导出流）直接透传
    if (!body || typeof body.code !== 'number') return response

    if (body.code === ResultCode.SUCCESS) {
      response.data = body.data
      return response
    }

    // 业务拒绝也要弹：抛之前先把服务端原文告知用户（错误码与 traceId 已在 body 里）
    notifyFailure(body.code, body.message ?? '', response.config.silentError === true)
    throw new ApiError(body.code, body.message, body.traceId)
  },
  async (error) => {
    const config = error.config as InternalAxiosRequestConfig | undefined
    const status = error.response?.status
    const body = error.response?.data as ApiResult<unknown> | undefined

    // 网络层失败（后端没起、跨域被拦、超时）没有 response body，单独给可定位的提示
    if (!error.response) {
      ElMessage.error('网络不可用，请确认后端服务已启动')
      return Promise.reject(new ApiError(-1, error.message ?? '网络异常'))
    }

    const code = body?.code ?? (status === 401 ? ResultCode.UNAUTHORIZED : ResultCode.SYSTEM_ERROR)

    if (code === ResultCode.UNAUTHORIZED && config && !config._retriedAfterRefresh && !isAuthEndpoint(config.url)) {
      const ok = await refreshOnce()
      if (ok) {
        config._retriedAfterRefresh = true
        return http.request(config)
      }
      session?.forceLogout()
      ElMessage.warning('登录已过期，请重新登录')
      return Promise.reject(new ApiError(code, '登录已过期', body?.traceId))
    }

    if (code === ResultCode.UNAUTHORIZED && !isAuthEndpoint(config?.url)) {
      session?.forceLogout()
    }

    const message = messageFor(code, body?.message ?? '')
    const silent = (config as AxiosRequestConfig | undefined)?.silentError === true
    if (!silent) {
      ElMessage.error(code >= ResultCode.SYSTEM_ERROR ? `${message}（${body?.traceId ?? '无 traceId'}）` : message)
    }
    return Promise.reject(new ApiError(code, message, body?.traceId))
  },
)

/** 统一出口：页面拿到的就是 data 本体，错误一律以 ApiError 抛出。 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  return response.data as unknown as T
}

export default http

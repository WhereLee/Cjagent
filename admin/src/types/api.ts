/**
 * 与后端 `R<T>` / `PageResult` 一一对应的契约类型。
 *
 * 这里手抄而不是自动从 OpenAPI 生成，是有意的：生成器要引 extra 工具链，
 * 而底座阶段接口数量很小；等阶段 1 接口上百时再切到 openapi-typescript 生成。
 */

export interface ApiResult<T> {
  code: number
  message: string
  /** 后端 data 为 null 时不序列化该字段，所以这里是可选属性 */
  data?: T
  traceId?: string
  timestamp?: string
}

export interface PageResult<T> {
  pageNum: number
  pageSize: number
  total: number
  pages: number
  records: T[]
}

export interface PageQuery {
  pageNum?: number
  pageSize?: number
  /** 实体属性名，必须在后端为该接口声明的白名单内 */
  orderBy?: string
  asc?: boolean
}

/**
 * 错误码常量，与后端 com.wherelee.cabinet.common.api.ResultCode 对应。
 * 前端必须按 code 分支（HTTP 状态只表达大类），否则"去登录 / 提示无权限 / 提示重试"
 * 三种处理会互相误触发。
 */
export const ResultCode = {
  SUCCESS: 0,
  BIZ_ERROR: 10000,
  PARAM_INVALID: 40000,
  UNAUTHORIZED: 40100,
  FORBIDDEN: 40300,
  TENANT_INVALID: 40301,
  IDEMPOTENT_REJECT: 40900,
  TOO_MANY_REQUESTS: 42900,
  SYSTEM_ERROR: 50000,
  MIDDLEWARE_UNAVAILABLE: 50100,
} as const

export type ResultCodeValue = (typeof ResultCode)[keyof typeof ResultCode]

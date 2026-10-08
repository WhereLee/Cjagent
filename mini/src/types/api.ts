/**
 * 与后端 `R<T>` / `PageResult` / `ResultCode` 一一对应的契约类型。
 *
 * <b>这份文件与 admin/src/types/api.ts 是重复的</b>，已按红线登记在
 * docs/架构约定.md §7：真正消除漂移的手段是后端的契约测试
 * （BaselineConventionsTest 断言错误码值与响应结构），两边都只是消费方。
 * 之所以现在就抽成共享包不划算：admin 是 Vite、mini 是 uni-app 编译器，
 * 共享 TS 源码要引入 workspace + 构建链，代价大于当前两处小文件的收益。
 */

export interface ApiResult<T> {
  code: number
  message: string
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
  orderBy?: string
  asc?: boolean
}

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

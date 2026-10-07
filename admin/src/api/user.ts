import { request } from '@/api/http'
import type { PageQuery, PageResult } from '@/types/api'

export interface UserView {
  id: string
  username: string
  realName?: string
  /** 后端已脱敏（138****8000）。前端拿不到全文，也不需要拿到 */
  phone?: string
  status: number
  lastLoginAt?: string
  createTime?: string
}

export interface UserQuery {
  keyword?: string
  status?: number
}

/**
 * 本接口允许排序的属性，与后端 UserQueryService.SORTABLE 对应。
 *
 * 两份清单必须同时改：前端少了会把可排序列做成不可点，前端多了会被后端 400 拒掉。
 * 真正的权威在服务端白名单，这里只为交互体验。
 */
export const USER_SORTABLE = ['id', 'username', 'realName', 'status', 'createTime', 'lastLoginAt'] as const

export function listUsers(query: UserQuery & PageQuery) {
  return request<PageResult<UserView>>({
    url: '/api/admin/users',
    method: 'get',
    params: query,
  })
}

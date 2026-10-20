// B 端接口层：所有调用只消费后端既有路由（/api/admin/**），不新增字段与概念。
// 入参出参类型以 schema-admin.ts（生成物）为准；生成物丢失的行类型见 contracts.ts。
import { request } from './http'
import type {
  AdminLoginRequest,
  ArrearsView,
  AskCommand,
  Brief,
  CallDoneCommand,
  CustomerStatusRequest,
  DepositView,
  Detail,
  Draft,
  EventView,
  EvidenceView,
  ForceOpenRequest,
  LedgerView,
  OpsAnswer,
  OpsReport,
  PhoneNoteCommand,
  ReasonRequest,
  RefreshRequest,
  ResolveCommand,
  ResolveRequest,
  TokenView,
  UserView,
  View,
} from './schema-admin'
import type { CurrentAccount, NoticeRow, OrderDetail, OrderRow, Paged, SummaryRow, WorkOrderFamily, WorkOrderRow } from './contracts'

// ── 认证 /api/admin/auth ──────────────────────────────────────────
export const adminLogin = (body: AdminLoginRequest) =>
  request<TokenView>({ url: '/api/admin/auth/login', method: 'post', data: body })

export const adminRefresh = (body: RefreshRequest) =>
  request<TokenView>({ url: '/api/admin/auth/refresh', method: 'post', data: body })

export const adminLogout = (body: RefreshRequest) =>
  request<void>({ url: '/api/admin/auth/logout', method: 'post', data: body })

export const adminMe = () => request<CurrentAccount>({ url: '/api/admin/auth/me', method: 'get' })

// ── 工单 /api/admin/work-orders ───────────────────────────────────
export interface PageQuery {
  pageNum?: number
  pageSize?: number
  orderBy?: string
}

export const fetchOnlineQueue = (params: PageQuery & { openOnly?: boolean }) =>
  request<Paged<WorkOrderRow>>({ url: '/api/admin/work-orders/online', method: 'get', params })

export const fetchFieldQueue = (params: PageQuery & { openOnly?: boolean }) =>
  request<Paged<WorkOrderRow>>({ url: '/api/admin/work-orders/field', method: 'get', params })

export const fetchTimeline = (id: string) =>
  request<EventView[]>({ url: `/api/admin/work-orders/${id}/timeline`, method: 'get' })

/** 父子单关系：详情页双向跳转，子单看得到父单结论 */
export const fetchWorkOrderFamily = (id: string) =>
  request<WorkOrderFamily>({ url: `/api/admin/work-orders/${id}/family`, method: 'get' })

export const fetchEvidence = (id: string) =>
  request<EvidenceView[]>({ url: `/api/admin/work-orders/${id}/evidence`, method: 'get' })

export const claimWorkOrder = (id: string) =>
  request<void>({ url: `/api/admin/work-orders/${id}/claim`, method: 'post' })

/** requestId 由前端生成（同一"处理意图"复用同一个）；返回派生子单 ID */
export const resolveWorkOrder = (id: string, body: ResolveCommand) =>
  request<string>({ url: `/api/admin/work-orders/${id}/resolve`, method: 'post', data: body })

export const phoneNote = (id: string, body: PhoneNoteCommand) =>
  request<void>({ url: `/api/admin/work-orders/${id}/phone-note`, method: 'post', data: body })

/** AI 简报不落库：仅供当前业务员参考，UI 不做"保存简报"按钮。模型调用可能超过默认 30s，单独放宽 */
export const aiBrief = (id: string) =>
  request<Brief>({ url: `/api/admin/work-orders/${id}/ai-brief`, method: 'post', timeout: 120000 })

export const fetchPendingCalls = () =>
  request<NoticeRow[]>({ url: '/api/admin/work-orders/pending-calls', method: 'get' })

export const callDone = (id: string, body: CallDoneCommand) =>
  request<void>({ url: `/api/admin/work-orders/notices/${id}/call-done`, method: 'post', data: body })

// ── 订单查询 /api/admin/orders ─────────────────────────────────────
// 只读：后台目前没有对订单的写操作，页面不提供任何“修改/退款”按钮。
export interface OrderQuery extends PageQuery {
  orderNo?: string
  phone?: string
  status?: string
  /** yyyy-MM-dd，按创建时间过滤（含当天） */
  from?: string
  to?: string
}

export const fetchOrders = (params: OrderQuery) =>
  request<Paged<OrderRow>>({ url: '/api/admin/orders', method: 'get', params })

export const fetchOrderDetail = (orderNo: string) =>
  request<OrderDetail>({ url: `/api/admin/orders/${orderNo}`, method: 'get' })

// ── 柜机运营 /api/admin/locker ────────────────────────────────────
export const fetchAnomalies = (params: PageQuery & { anomaly?: string }) =>
  request<Paged<LedgerView>>({ url: '/api/admin/locker/compartments/anomalies', method: 'get', params })

export const fetchAnomalySummary = () =>
  request<SummaryRow[]>({ url: '/api/admin/locker/compartments/anomalies/summary', method: 'get' })

export const fetchCompartmentDetail = (compartmentId: string) =>
  request<Detail>({ url: `/api/admin/locker/compartments/${compartmentId}`, method: 'get' })

export const resolveCompartment = (compartmentId: string, body: ResolveRequest) =>
  request<void>({ url: `/api/admin/locker/compartments/${compartmentId}/resolve`, method: 'post', data: body })

/** 强制开柜：confirmed 必须为 true（后端校验），UI 上必须先二次确认 */
export const forceOpen = (compartmentId: string, body: ForceOpenRequest) =>
  request<void>({ url: `/api/admin/locker/compartments/${compartmentId}/force-open`, method: 'post', data: body })

export const rebuildFreeSet = (cabinetId: string) =>
  request<void>({ url: `/api/admin/locker/cabinets/${cabinetId}/free-set/rebuild`, method: 'post' })

export const fetchArrears = (params: PageQuery) =>
  request<Paged<ArrearsView>>({ url: '/api/admin/locker/arrears', method: 'get', params })

export const fetchUnrefundedDeposits = (params: PageQuery) =>
  request<Paged<DepositView>>({ url: '/api/admin/locker/deposits/unrefunded', method: 'get', params })

export const changeCustomerStatus = (customerId: string, body: CustomerStatusRequest) =>
  request<void>({ url: `/api/admin/locker/customers/${customerId}/status`, method: 'post', data: body })

// ── 定价策略 /api/admin/pricing ───────────────────────────────────
export const fetchPricingRules = () =>
  request<View[]>({ url: '/api/admin/pricing/rules', method: 'get' })

export const fetchEffectiveRule = (siteId?: string) =>
  request<View>({ url: '/api/admin/pricing/effective', method: 'get', params: siteId ? { siteId } : {} })

export const previewRule = (body: Draft) =>
  request<Record<string, unknown>>({ url: '/api/admin/pricing/rules/preview', method: 'post', data: body })

export const publishRule = (body: Draft) =>
  request<View>({ url: '/api/admin/pricing/rules', method: 'post', data: body })

export const rollbackRule = (ruleId: string, body: ReasonRequest) =>
  request<void>({ url: `/api/admin/pricing/rules/${ruleId}/rollback`, method: 'post', data: body })

// ── 运维诊断 /api/admin/ops ───────────────────────────────────────
/** AI 诊断是分钟级慢接口，加载态要按分钟级预期；限流 3 次/分。signal 用于用户主动取消 */
export const opsDiagnose = (signal?: AbortSignal) =>
  request<OpsReport>({ url: '/api/admin/ops/diagnose', method: 'post', timeout: 180000, signal })

export const opsAsk = (body: AskCommand, signal?: AbortSignal) =>
  request<OpsAnswer>({ url: '/api/admin/ops/ask', method: 'post', data: body, timeout: 180000, signal })

// ── 账号 /api/admin/users ─────────────────────────────────────────
export const fetchUsers = (params: PageQuery & { keyword?: string; status?: number }) =>
  request<Paged<UserView>>({ url: '/api/admin/users', method: 'get', params })

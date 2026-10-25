// B 端接口层：所有调用只消费后端既有路由（/api/admin/**），不新增字段与概念。
// 入参出参类型以 schema-admin.ts（生成物）为准；生成物丢失的行类型见 contracts.ts。
import { request } from './http'
import type {
  AdminLoginRequest,
  AiJobView,
  ArrearsView,
  AskCommand,
  CallDoneCommand,
  CustomerStatusRequest,
  DeadLetterView,
  DepositView,
  Detail,
  Draft,
  EventView,
  EvidenceView,
  ForceOpenRequest,
  LedgerView,
  PhoneNoteCommand,
  PricingRuleView,
  ReasonRequest,
  RefreshRequest,
  ResolveCommand,
  ResolveRequest,
  TokenView,
  UserView,
} from './schema-admin'
import type { Paged } from './contracts'
import type { Family, CurrentAccountView, NoticeView, OrderDetail, OrderRow, SummaryView, WorkOrderView } from './schema-admin'

// ── 认证 /api/admin/auth ──────────────────────────────────────────
export const adminLogin = (body: AdminLoginRequest) =>
  request<TokenView>({ url: '/api/admin/auth/login', method: 'post', data: body })

export const adminRefresh = (body: RefreshRequest) =>
  request<TokenView>({ url: '/api/admin/auth/refresh', method: 'post', data: body })

export const adminLogout = (body: RefreshRequest) =>
  request<void>({ url: '/api/admin/auth/logout', method: 'post', data: body })

export const adminMe = () => request<CurrentAccountView>({ url: '/api/admin/auth/me', method: 'get' })

// ── 工单 /api/admin/work-orders ───────────────────────────────────
export interface PageQuery {
  pageNum?: number
  pageSize?: number
  orderBy?: string
}

export const fetchOnlineQueue = (params: PageQuery & { openOnly?: boolean }) =>
  request<Paged<WorkOrderView>>({ url: '/api/admin/work-orders/online', method: 'get', params })

export const fetchFieldQueue = (params: PageQuery & { openOnly?: boolean }) =>
  request<Paged<WorkOrderView>>({ url: '/api/admin/work-orders/field', method: 'get', params })

export const fetchTimeline = (id: string) =>
  request<EventView[]>({ url: `/api/admin/work-orders/${id}/timeline`, method: 'get' })

/** 父子单关系：详情页双向跳转，子单看得到父单结论 */
export const fetchWorkOrderFamily = (id: string) =>
  request<Family>({ url: `/api/admin/work-orders/${id}/family`, method: 'get' })

export const fetchEvidence = (id: string) =>
  request<EvidenceView[]>({ url: `/api/admin/work-orders/${id}/evidence`, method: 'get' })

export const claimWorkOrder = (id: string) =>
  request<void>({ url: `/api/admin/work-orders/${id}/claim`, method: 'post' })

/** 暂停 SLA：reason=WAITING_CUSTOMER 表示等待用户；不传为普通暂停（等配件等） */
export const pauseWorkOrder = (id: string, reason?: 'WAITING_CUSTOMER') =>
  request<void>({
    url: `/api/admin/work-orders/${id}/sla/pause`,
    method: 'post',
    data: reason ? { reason } : {},
  })

/** 恢复 SLA：把暂停时长补回响应/解决时限 */
export const resumeWorkOrder = (id: string) =>
  request<void>({ url: `/api/admin/work-orders/${id}/sla/resume`, method: 'post' })

/** requestId 由前端生成（同一"处理意图"复用同一个）；返回派生子单 ID */
export const resolveWorkOrder = (id: string, body: ResolveCommand) =>
  request<string>({ url: `/api/admin/work-orders/${id}/resolve`, method: 'post', data: body })

export const phoneNote = (id: string, body: PhoneNoteCommand) =>
  request<void>({ url: `/api/admin/work-orders/${id}/phone-note`, method: 'post', data: body })

/** AI 简报不落库：仅供当前业务员参考，UI 不做“保存简报”按钮。批 7 起它只回作业号 */
// AI 长任务：提交只拿作业号（批 7）。产出由作业通道异步写，前端用 waitAiJob 轮询取回。
export const aiBrief = (id: string) =>
  request<string>({ url: `/api/admin/work-orders/${id}/ai-brief`, method: 'post' })

/** 查一次作业。别人的作业后端返回 data=null（当不存在）。 */
export const getAiJob = (id: string) =>
  request<AiJobView>({ url: `/api/admin/ai-jobs/${id}`, method: 'get' })

/**
 * 轮询作业直到出结果。
 *
 * 间隔 1 秒、上限 60 秒都是**按后端预算推的**，不是新业务规则：后端单次 AI 预算是
 * `timeout-ms × 2`（两轮工具循环，共 40 秒），这里留到 60 秒是给它失败的余地；
 * 1 秒一次对“人工点按钮”这个触发频率可以忽略。
 */
export async function waitAiJob<T>(
  jobId: string,
  timeoutMs = 60_000,
  cancelled?: () => boolean,
): Promise<T> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (cancelled?.()) {
      // 取消只停“等”：作业在后端会跑完，结果丢掉即可（作业表自然过期）
      throw new Error('已取消')
    }
    const view = await getAiJob(jobId)
    if (view?.status === 'DONE') {
      return view.result as T
    }
    if (view?.status === 'FAILED') {
      throw new Error(view.error || 'AI 生成失败，请稍后重试')
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error('AI 生成超时，请稍后重试')
}

export const fetchPendingCalls = () =>
  request<NoticeView[]>({ url: '/api/admin/work-orders/pending-calls', method: 'get' })

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
  request<SummaryView[]>({ url: '/api/admin/locker/compartments/anomalies/summary', method: 'get' })

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
  request<PricingRuleView[]>({ url: '/api/admin/pricing/rules', method: 'get' })

export const fetchEffectiveRule = (siteId?: string) =>
  request<PricingRuleView>({ url: '/api/admin/pricing/effective', method: 'get', params: siteId ? { siteId } : {} })

export const previewRule = (body: Draft) =>
  request<Record<string, unknown>>({ url: '/api/admin/pricing/rules/preview', method: 'post', data: body })

export const publishRule = (body: Draft) =>
  request<PricingRuleView>({ url: '/api/admin/pricing/rules', method: 'post', data: body })

export const rollbackRule = (ruleId: string, body: ReasonRequest) =>
  request<void>({ url: `/api/admin/pricing/rules/${ruleId}/rollback`, method: 'post', data: body })

// ── 运维诊断 /api/admin/ops ───────────────────────────────────────
/** AI 诊断/问答是长任务：提交是毫秒级（只写作业行），生成在后台跑；限流 3 次/分 */
export const opsDiagnose = (signal?: AbortSignal) =>
  request<string>({ url: '/api/admin/ops/diagnose', method: 'post', timeout: 10000, signal })

export const opsAsk = (body: AskCommand, signal?: AbortSignal) =>
  request<string>({ url: '/api/admin/ops/ask', method: 'post', data: body, timeout: 10000, signal })

// ── 死信 /api/admin/ops ──────────────────────────────────────────
export interface DeadLetterQuery extends PageQuery {
  /** OPEN 待处理 / REPLAYED 已重放；不传则两种都返回 */
  status?: string
}

/** 死信分页。带正文：重放前必须先看清这条消息到底是什么 */
export const fetchDeadLetters = (params: DeadLetterQuery) =>
  request<Paged<DeadLetterView>>({ url: '/api/admin/ops/dead-letters', method: 'get', params })

/**
 * 重放一条死信：把消息重新投回原来的业务 topic。
 *
 * 返回 `false` 不是错误，是两种**正常**情况：已经重放过；或消息缺租户信息（坏消息，只能人工看）。
 */
export const replayDeadLetter = (id: string) =>
  request<boolean>({ url: `/api/admin/ops/dead-letter/${id}/replay`, method: 'post' })

// ── 账号 /api/admin/users ─────────────────────────────────────────
export const fetchUsers = (params: PageQuery & { keyword?: string; status?: number }) =>
  request<Paged<UserView>>({ url: '/api/admin/users', method: 'get', params })

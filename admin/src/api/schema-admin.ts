// 本文件由 scripts/gen-api-types.mjs 自动生成，请勿手改。
// 来源：scripts/generated/api-docs-1-admin.json（后端 /v3/api-docs，B 端运营后台（/api/admin/**））
// 重新生成：先起后端拉契约，再跑 node scripts/gen-api-types.mjs
//
// integer format:int64 一律映射为 string：后端 Long 全局序列化为字符串（雪花 ID
// 超出 JS 安全整数），前端禁止用 parseInt/Number 还原这些字段。

export interface RVoid {
  code?: number
  message?: string
  data?: unknown
  traceId?: string
  timestamp?: string
}

export interface PauseRequest {
  reason?: "WAITING_CUSTOMER"
}

export interface ResolveCommand {
  requestId: string
  resultNote: string
  needOffline?: boolean
  closeReason?: "MANUAL" | "FACT_RECOVERED" | "USER_CONFIRMED" | "MERGED" | "CANCELLED"
}

export interface RLong {
  code?: number
  message?: string
  data?: string
  traceId?: string
  timestamp?: string
}

export interface PhoneNoteCommand {
  resultNote: string
}

export interface CallDoneCommand {
  resultNote: string
}

export interface Draft {
  siteId?: string
  effectiveAt?: string
  reason?: string
  depositPoints?: string
  freeMinutes?: number
  dailyCapHours?: number
  capDays?: number
  toleranceMinutes?: number
  remoteCloseHours?: number
  unitSmall?: string
  unitMedium?: string
  unitLarge?: string
}

export interface PricingRuleView {
  id?: string
  siteId?: string
  version?: number
  status?: string
  depositPoints?: string
  freeMinutes?: number
  dailyCapHours?: number
  capDays?: number
  toleranceMinutes?: number
  remoteCloseHours?: number
  unitSmall?: string
  unitMedium?: string
  unitLarge?: string
  effectiveAt?: string
  publishedBy?: string
  reason?: string
}

export interface RPricingRuleView {
  code?: number
  message?: string
  data?: PricingRuleView
  traceId?: string
  timestamp?: string
}

export interface ReasonRequest {
  reason: string
}

export interface RMapStringObject {
  code?: number
  message?: string
  data?: Record<string, unknown>
  traceId?: string
  timestamp?: string
}

export interface RBoolean {
  code?: number
  message?: string
  data?: boolean
  traceId?: string
  timestamp?: string
}

export interface AskCommand {
  question: string
}

export interface CustomerStatusRequest {
  status: number
  reason: string
}

export interface ResolveRequest {
  note: string
}

export interface ForceOpenRequest {
  confirmed?: boolean
  reason: string
}

export interface RefreshRequest {
  refreshToken: string
}

export interface RTokenView {
  code?: number
  message?: string
  data?: TokenView
  traceId?: string
  timestamp?: string
}

export interface TokenView {
  accessToken?: string
  refreshToken?: string
  tokenType?: string
  expiresIn?: string
  refreshExpiresIn?: string
}

export interface AdminLoginRequest {
  tenantCode: string
  username: string
  password: string
}

export interface EventView {
  eventType?: string
  actorType?: string
  operatorId?: string
  traceId?: string
  detail?: string
  at?: string
}

export interface RListEventView {
  code?: number
  message?: string
  data?: EventView[]
  traceId?: string
  timestamp?: string
}

export interface Family {
  self?: WorkOrderView
  parent?: WorkOrderView
  children?: WorkOrderView[]
}

export interface RFamily {
  code?: number
  message?: string
  data?: Family
  traceId?: string
  timestamp?: string
}

export interface WorkOrderView {
  id?: string
  kind?: string
  source?: string
  type?: string
  intent?: string
  category?: string
  priority?: string
  impact?: string
  urgency?: string
  urgent?: boolean
  status?: string
  queueSide?: string
  assigneeGroup?: string
  slotNo?: string
  cabinetNo?: string
  siteAddress?: string
  orderNo?: string
  customerPhone?: string
  title?: string
  detection?: string
  resultNote?: string
  closeReason?: string
  assigneeId?: string
  parentId?: string
  parentResultNote?: string
  dueAt?: string
  responseDueAt?: string
  resolveDueAt?: string
  firstResponseAt?: string
  closedAt?: string
  createTime?: string
  handoffJson?: string
  reopenedCount?: number
  slaPauseReason?: string
  csatScore?: number
  csatComment?: string
}

export interface EvidenceView {
  moment?: string
  source?: string
  presence?: string
  confidence?: number
  aiLabel?: string
  photoRef?: string
  note?: string
  at?: string
}

export interface RListEvidenceView {
  code?: number
  message?: string
  data?: EvidenceView[]
  traceId?: string
  timestamp?: string
}

export interface NoticeView {
  id?: string
  channel?: string
  kind?: string
  content?: string
  orderNo?: string
  workOrderId?: string
  createTime?: string
  doneAt?: string
  doneNote?: string
}

export interface RListNoticeView {
  code?: number
  message?: string
  data?: NoticeView[]
  traceId?: string
  timestamp?: string
}

export interface PageQuery {
  pageNum?: string
  pageSize?: string
  orderBy?: string
  asc?: boolean
}

export interface PageResultWorkOrderView {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: WorkOrderView[]
}

export interface RPageResultWorkOrderView {
  code?: number
  message?: string
  data?: PageResultWorkOrderView
  traceId?: string
  timestamp?: string
}

export interface PageResultUserView {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: UserView[]
}

export interface RPageResultUserView {
  code?: number
  message?: string
  data?: PageResultUserView
  traceId?: string
  timestamp?: string
}

export interface UserView {
  id?: string
  username?: string
  realName?: string
  phone?: string
  status?: number
  lastLoginAt?: string
  createTime?: string
}

export interface RString {
  code?: number
  message?: string
  data?: string
  traceId?: string
  timestamp?: string
}

export interface RListPricingRuleView {
  code?: number
  message?: string
  data?: PricingRuleView[]
  traceId?: string
  timestamp?: string
}

export interface OrderRow {
  id?: string
  orderNo?: string
  status?: string
  sizeType?: string
  customerId?: string
  customerName?: string
  siteName?: string
  cabinetNo?: string
  slotNo?: string
  prepayPoints?: string
  settledPoints?: string
  arrearsPoints?: string
  createTime?: string
  startedAt?: string
  finishedAt?: string
}

export interface PageResultOrderRow {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: OrderRow[]
}

export interface RPageResultOrderRow {
  code?: number
  message?: string
  data?: PageResultOrderRow
  traceId?: string
  timestamp?: string
}

export interface BillLine {
  type?: string
  amount?: string
  balanceAfter?: string
  remark?: string
  at?: string
}

export interface BillView {
  orderNo?: string
  status?: string
  sizeType?: string
  startedAt?: string
  finishedAt?: string
  billedMinutes?: string
  unitPointsPerHour?: string
  freeMinutes?: number
  dailyCapHours?: number
  capDays?: number
  billedHours?: number
  chargePoints?: string
  prepayPoints?: string
  settledPoints?: string
  arrearsPoints?: string
  lines?: BillLine[]
}

export interface CommandLine {
  action?: string
  state?: string
  retryCount?: number
  sentAt?: string
  ackedAt?: string
  lastError?: string
}

export interface EvidenceLine {
  source?: string
  presence?: string
  moment?: string
  confidence?: number
  aiLabel?: string
  note?: string
  at?: string
}

export interface OrderDetail {
  row?: OrderRow
  customerPhone?: string
  closeReason?: string
  disputeState?: string
  aiReviewCount?: number
  tempOpenCount?: number
  voucherWrongCount?: number
  voucherLocked?: boolean
  leftoverReported?: boolean
  depositPoints?: string
  remoteClosePoints?: string
  bill?: BillView
  commands?: CommandLine[]
  evidence?: EvidenceLine[]
  workOrders?: WorkOrderLine[]
}

export interface ROrderDetail {
  code?: number
  message?: string
  data?: OrderDetail
  traceId?: string
  timestamp?: string
}

export interface WorkOrderLine {
  id?: string
  status?: string
  category?: string
  priority?: string
  closeReason?: string
  title?: string
  createTime?: string
}

export interface DeadLetterView {
  id?: string
  topic?: string
  tag?: string
  dlqTopic?: string
  msgKey?: string
  payload?: string
  error?: string
  reconsumeTimes?: number
  status?: string
  receivedAt?: string
  replayedAt?: string
}

export interface PageResultDeadLetterView {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: DeadLetterView[]
}

export interface RPageResultDeadLetterView {
  code?: number
  message?: string
  data?: PageResultDeadLetterView
  traceId?: string
  timestamp?: string
}

export interface DepositView {
  depositId?: string
  orderNo?: string
  customerId?: string
  points?: string
  depositStatus?: string
  heldHours?: string
}

export interface PageResultDepositView {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: DepositView[]
}

export interface RPageResultDepositView {
  code?: number
  message?: string
  data?: PageResultDepositView
  traceId?: string
  timestamp?: string
}

export interface BizCompartment {
  id?: string
  createTime?: string
  updateTime?: string
  deleted?: number
  tenantId?: string
  cabinetId?: string
  slotNo?: string
  sizeType?: "LARGE" | "MEDIUM" | "SMALL"
  status?: "FREE" | "RESERVED" | "OCCUPIED" | "FAULT" | "MAINTENANCE"
  currentOrderId?: string
  doorOpenAt?: string
  doorOpenedBy?: string
  doorClosedAt?: string
  doorClosedBy?: string
  presence?: "PRESENT" | "ABSENT" | "UNKNOWN"
  presenceCheckedAt?: string
  anomaly?: "DOOR_OPEN" | "CONTENT_LEFT" | "CONTENT_UNVERIFIED" | "SENSOR_CONFLICT"
  anomalyAt?: string
  anomalyReason?: string
  version?: number
}

export interface Detail {
  compartment?: BizCompartment
  evidence?: EvidenceSnapshot[]
  currentOrder?: OrderSnapshot
}

export interface EvidenceSnapshot {
  moment?: string
  source?: string
  presence?: string
  confidence?: number
  aiLabel?: string
  photoRef?: string
  note?: string
  at?: string
}

export interface OrderSnapshot {
  id?: string
  orderNo?: string
  customerId?: string
  cabinetId?: string
  slotId?: string
  status?: string
  sizeType?: string
}

export interface RDetail {
  code?: number
  message?: string
  data?: Detail
  traceId?: string
  timestamp?: string
}

export interface LedgerView {
  compartmentId?: string
  cabinetId?: string
  cabinetNo?: string
  slotNo?: string
  anomaly?: string
  anomalyAt?: string
  stuckMinutes?: string
  anomalyReason?: string
  siteId?: string
  siteName?: string
  currentOrderId?: string
}

export interface PageResultLedgerView {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: LedgerView[]
}

export interface RPageResultLedgerView {
  code?: number
  message?: string
  data?: PageResultLedgerView
  traceId?: string
  timestamp?: string
}

export interface RListSummaryView {
  code?: number
  message?: string
  data?: SummaryView[]
  traceId?: string
  timestamp?: string
}

export interface SummaryView {
  anomaly?: string
  rowsCount?: string
  longestMinutes?: string
}

export interface ArrearsView {
  customerId?: string
  arrearsPoints?: string
  arrearsOrders?: number
  lastFinishedAt?: string
}

export interface PageResultArrearsView {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: ArrearsView[]
}

export interface RPageResultArrearsView {
  code?: number
  message?: string
  data?: PageResultArrearsView
  traceId?: string
  timestamp?: string
}

export interface CurrentAccountView {
  userId?: string
  username?: string
  tenantId?: string
  end?: string
  authorities?: string[]
}

export interface RCurrentAccountView {
  code?: number
  message?: string
  data?: CurrentAccountView
  traceId?: string
  timestamp?: string
}

export interface AiJobView {
  id?: string
  jobType?: string
  status?: string
  result?: unknown
  error?: string
  createTime?: string
  updateTime?: string
}

export interface RAiJobView {
  code?: number
  message?: string
  data?: AiJobView
  traceId?: string
  timestamp?: string
}

/** 接口操作表：key 为 "method path"（method 小写），入参与出参类型来自契约 */
export interface OperationTypes {
  "post /api/admin/work-orders/{id}/sla/resume": {
    summary: "恢复SLA"
    req: null
    res: RVoid
  }
  "post /api/admin/work-orders/{id}/sla/pause": {
    summary: "暂停SLA"
    req: PauseRequest
    res: RVoid
  }
  "post /api/admin/work-orders/{id}/resolve": {
    summary: "处理并反馈"
    req: ResolveCommand
    res: RLong
  }
  "post /api/admin/work-orders/{id}/phone-note": {
    summary: "记录电话联系"
    req: PhoneNoteCommand
    res: RVoid
  }
  "post /api/admin/work-orders/{id}/claim": {
    summary: "认领工单"
    req: null
    res: RVoid
  }
  "post /api/admin/work-orders/{id}/ai-brief": {
    summary: "AI 工单简报"
    req: null
    res: RLong
  }
  "post /api/admin/work-orders/triage/reload": {
    summary: "刷新分诊规则缓存"
    req: null
    res: RVoid
  }
  "post /api/admin/work-orders/notices/{id}/call-done": {
    summary: "回填电话结果"
    req: CallDoneCommand
    res: RVoid
  }
  "get /api/admin/pricing/rules": {
    summary: "策略列表"
    req: null
    res: RListPricingRuleView
  }
  "post /api/admin/pricing/rules": {
    summary: "发布一版策略"
    req: Draft
    res: RPricingRuleView
  }
  "post /api/admin/pricing/rules/{ruleId}/rollback": {
    summary: "回滚到指定版本"
    req: ReasonRequest
    res: RPricingRuleView
  }
  "post /api/admin/pricing/rules/preview": {
    summary: "发布前预览"
    req: Draft
    res: RMapStringObject
  }
  "post /api/admin/ops/diagnose": {
    summary: "AI 运维诊断"
    req: null
    res: RLong
  }
  "post /api/admin/ops/dead-letter/{id}/replay": {
    summary: "重放一条死信"
    req: null
    res: RBoolean
  }
  "post /api/admin/ops/ask": {
    summary: "运维问答"
    req: AskCommand
    res: RLong
  }
  "post /api/admin/locker/customers/{customerId}/status": {
    summary: "禁用/启用客户"
    req: CustomerStatusRequest
    res: RVoid
  }
  "post /api/admin/locker/compartments/{compartmentId}/resolve": {
    summary: "清柜：解除格口异常"
    req: ResolveRequest
    res: RVoid
  }
  "post /api/admin/locker/compartments/{compartmentId}/force-open": {
    summary: "后台强制开柜"
    req: ForceOpenRequest
    res: RVoid
  }
  "post /api/admin/locker/cabinets/{cabinetId}/free-set/rebuild": {
    summary: "重建空闲格口集合"
    req: null
    res: RVoid
  }
  "post /api/admin/auth/refresh": {
    summary: "刷新凭证"
    req: RefreshRequest
    res: RTokenView
  }
  "post /api/admin/auth/logout": {
    summary: "注销"
    req: RefreshRequest
    res: RVoid
  }
  "post /api/admin/auth/login": {
    summary: "账号密码登录"
    req: AdminLoginRequest
    res: RTokenView
  }
  "get /api/admin/work-orders/{id}/timeline": {
    summary: "工单流转留痕"
    req: null
    res: RListEventView
  }
  "get /api/admin/work-orders/{id}/family": {
    summary: "父子单关系"
    req: null
    res: RFamily
  }
  "get /api/admin/work-orders/{id}/evidence": {
    summary: "格口证据时间线"
    req: null
    res: RListEvidenceView
  }
  "get /api/admin/work-orders/pending-calls": {
    summary: "待联系客户的电话任务"
    req: null
    res: RListNoticeView
  }
  "get /api/admin/work-orders/online": {
    summary: "线上工单队列"
    req: null
    res: RPageResultWorkOrderView
  }
  "get /api/admin/work-orders/field": {
    summary: "线下工单队列"
    req: null
    res: RPageResultWorkOrderView
  }
  "get /api/admin/users": {
    summary: "账号分页列表"
    req: null
    res: RPageResultUserView
  }
  "get /api/admin/probe/role": {
    summary: ""
    req: null
    res: RString
  }
  "get /api/admin/probe/permission": {
    summary: ""
    req: null
    res: RString
  }
  "get /api/admin/probe/authenticated": {
    summary: ""
    req: null
    res: RString
  }
  "get /api/admin/pricing/effective": {
    summary: "当前生效策略"
    req: null
    res: RPricingRuleView
  }
  "get /api/admin/orders": {
    summary: "订单分页列表"
    req: null
    res: RPageResultOrderRow
  }
  "get /api/admin/orders/{orderNo}": {
    summary: "订单详情"
    req: null
    res: ROrderDetail
  }
  "get /api/admin/ops/dead-letters": {
    summary: "死信列表"
    req: null
    res: RPageResultDeadLetterView
  }
  "get /api/admin/locker/deposits/unrefunded": {
    summary: "未退押金清单"
    req: null
    res: RPageResultDepositView
  }
  "get /api/admin/locker/compartments/{compartmentId}": {
    summary: "格口详情"
    req: null
    res: RDetail
  }
  "get /api/admin/locker/compartments/anomalies": {
    summary: "异常格口台账"
    req: null
    res: RPageResultLedgerView
  }
  "get /api/admin/locker/compartments/anomalies/summary": {
    summary: "异常积压概览"
    req: null
    res: RListSummaryView
  }
  "get /api/admin/locker/arrears": {
    summary: "欠费客户清单"
    req: null
    res: RPageResultArrearsView
  }
  "get /api/admin/auth/me": {
    summary: "当前账号与权限"
    req: null
    res: RCurrentAccountView
  }
  "get /api/admin/ai-jobs/{id}": {
    summary: "查 AI 作业"
    req: null
    res: RAiJobView
  }
}

export type OperationKey = keyof OperationTypes

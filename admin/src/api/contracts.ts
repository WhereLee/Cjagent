// 手写补齐类型：springdoc 对同名嵌套 record（XxxService.View）展开时互相覆盖，
// 这几个行视图在 schema-admin.ts 里丢失或错位，字段依据后端 Java record 原样映射
// （Long → string，与生成器同一条规则；依据文件见各注释）。
// 若后端改了这些 record 字段，这里必须同步改——它们是契约缺口的补偿，不是第二事实源。

/** 分页包（common/api/PageResult<T>）：records 元素类型随接口而变，用泛型表达 */
export interface Paged<T> {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: T[]
}

/**
 * 工单行（application/workorder/WorkOrderService.java 的 View）。
 * schema-admin.ts 里的 View 被 PriceRuleService.View 覆盖，不能用。
 */
export interface WorkOrderRow {
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
  /** WAITING_CUSTOMER=等待用户；空=未暂停或普通暂停 */
  slaPauseReason?: string
  /** 用户确认解决后的一次性评价（1–5 星） */
  csatScore?: number
  csatComment?: string
}

/** 父子单关系（application/workorder/WorkOrderService.Family）。 */
export interface WorkOrderFamily {
  self?: WorkOrderRow
  parent?: WorkOrderRow
  children?: WorkOrderRow[]
}

/**
 * 待联系电话任务行（application/notify/NoticeService.java 的 View）。
 * 同样被 PriceRuleService.View 撞名覆盖。
 */
export interface NoticeRow {
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

/** 当前账号（application/auth/dto/CurrentAccountView.java）：authorities 是权限点清单 */
export interface CurrentAccount {
  userId?: string
  username?: string
  tenantId?: string
  end?: string
  authorities?: string[]
}

/** 异常积压概览行（LockerConsoleService.SummaryView）；rowsCount/longestMinutes 为 Java long */
export interface SummaryRow {
  anomaly?: string
  rowsCount?: string
  longestMinutes?: string
}

// ── 订单查询（application/storage/AdminOrderQueryService）──────────────
// Long 字段（id/customerId）在线路上是字符串；long 点数与 int 计数保持数字，
// 与 JacksonConfig“ID 用包装 Long、计数用原始 long”的口径一致。

/** 订单行（AdminOrderQueryService.OrderRow） */
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
  prepayPoints?: number
  settledPoints?: number
  arrearsPoints?: number
  createTime?: string
  startedAt?: string
  finishedAt?: string
}

/** 设备动作行（OrderDetail.CommandLine） */
export interface OrderCommand {
  action?: string
  state?: string
  retryCount?: number
  sentAt?: string
  ackedAt?: string
  lastError?: string
}

/** 物检证据行（OrderDetail.EvidenceLine）；confidence 是 BigDecimal */
export interface OrderEvidence {
  source?: string
  presence?: string
  moment?: string
  confidence?: number
  aiLabel?: string
  note?: string
  at?: string
}

/** 关联工单行（OrderDetail.WorkOrderLine） */
export interface OrderWorkOrder {
  id?: string
  status?: string
  category?: string
  priority?: string
  closeReason?: string
  title?: string
  createTime?: string
}

/** 账单流水行（StorageQueryService.BillLine） */
export interface OrderBillLine {
  type?: string
  amount?: number
  balanceAfter?: number
  remark?: string
  at?: string
}

/** 账单（StorageQueryService.BillView）：参数全部取自这张单的快照，不用当前价重算 */
export interface OrderBill {
  orderNo?: string
  status?: string
  sizeType?: string
  startedAt?: string
  finishedAt?: string
  billedMinutes?: number
  unitPointsPerHour?: number
  freeMinutes?: number
  dailyCapHours?: number
  capDays?: number
  billedHours?: number
  chargePoints?: number
  prepayPoints?: number
  settledPoints?: number
  arrearsPoints?: number
  lines?: OrderBillLine[]
}

/** 订单详情（AdminOrderQueryService.OrderDetail）；历史单无快照时 bill 为 null */
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
  depositPoints?: number
  remoteClosePoints?: number
  bill?: OrderBill | null
  commands?: OrderCommand[]
  evidence?: OrderEvidence[]
  workOrders?: OrderWorkOrder[]
}

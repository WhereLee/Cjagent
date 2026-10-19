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

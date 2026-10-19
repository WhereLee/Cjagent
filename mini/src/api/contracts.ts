// 手写补齐类型：schema-mini.ts 里的 PickupRequest 是登录态取件的 body（可选 voucherCode），
// 与公开凭码取件的 PickupRequest（PublicPickupController.PickupRequest）撞名被覆盖。
// 字段依据 server/src/main/java/com/wherelee/cabinet/interfaces/mini/storage/PublicPickupController.java。

/** 匿名凭码取件入参（/api/public/storage/pickup）：双因子，都必填 */
export interface PublicPickupRequest {
  voucherCode: string
  /** 手机号后四位（四位数字） */
  phoneTail: string
}

/**
 * 用户侧工单视图（/api/mini/work-orders）：后端 WorkOrderService.CustomerView。
 * 故意不含交接 JSON、快照电话、指派对象等内部流转字段。
 */
export interface WorkOrderCustomerView {
  id?: string
  orderNo?: string
  title?: string
  status?: string
  category?: string
  priority?: string
  closeReason?: string
  resultNote?: string
  slotNo?: string
  cabinetNo?: string
  siteAddress?: string
  responseDueAt?: string
  resolveDueAt?: string
  closedAt?: string
  createTime?: string
  mergedIntoId?: string
  reopenedCount?: number
}

/** solved=true 确认已解决；false 反馈还没解决（重开） */
export interface WorkOrderConfirmRequest {
  solved: boolean
  note?: string
}

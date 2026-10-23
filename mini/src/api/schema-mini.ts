// 本文件由 scripts/gen-api-types.mjs 自动生成，请勿手改。
// 来源：scripts/generated/api-docs-2-mini.json（后端 /v3/api-docs，C 端用户端（/api/mini/** 与 /api/public/**））
// 重新生成：先起后端拉契约，再跑 node scripts/gen-api-types.mjs
//
// integer format:int64 一律映射为 string：后端 Long 全局序列化为字符串（雪花 ID
// 超出 JS 安全整数），前端禁止用 parseInt/Number 还原这些字段。

export interface ChatRequest {
  orderNo?: string
  message: string
}

export interface AgentReply {
  reply?: string
  escalated?: boolean
  ticketNo?: string
}

export interface RAgentReply {
  code?: number
  message?: string
  data?: AgentReply
  traceId?: string
  timestamp?: string
}

export interface CreateOrderCommand {
  requestId: string
  cabinetNo: string
  sizeType: string
}

export interface RStorageOrderView {
  code?: number
  message?: string
  data?: StorageOrderView
  traceId?: string
  timestamp?: string
}

export interface StorageOrderView {
  orderNo?: string
  cabinetNo?: string
  slotNo?: string
  allocatedSize?: string
  status?: string
  triedSlots?: number
  strategy?: string
  message?: string
}

export interface RVoucherView {
  code?: number
  message?: string
  data?: VoucherView
  traceId?: string
  timestamp?: string
}

export interface VoucherView {
  orderNo?: string
  cabinetNo?: string
  slotNo?: string
  sizeType?: string
  status?: string
  voucherCode?: string
  locked?: boolean
}

export interface PickupRequest {
  voucherCode?: string
}

export interface RString {
  code?: number
  message?: string
  data?: string
  traceId?: string
  timestamp?: string
}

export interface RechargeCommand {
  requestId: string
  points: string
}

export interface RRechargeView {
  code?: number
  message?: string
  data?: RechargeView
  traceId?: string
  timestamp?: string
}

export interface RechargeView {
  outTradeNo?: string
  points?: string
  balance?: string
  channel?: string
  status?: string
}

export interface RVoid {
  code?: number
  message?: string
  data?: unknown
  traceId?: string
  timestamp?: string
}

export interface MiniRefreshRequest {
  refreshToken: string
}

export interface MiniTokenView {
  accessToken?: string
  refreshToken?: string
  tokenType?: string
  expiresIn?: string
  refreshExpiresIn?: string
}

export interface RMiniTokenView {
  code?: number
  message?: string
  data?: MiniTokenView
  traceId?: string
  timestamp?: string
}

export interface CustomerLoginRequest {
  code: string
  tenantCode?: string
  phone?: string
}

export interface CustomerLoginView {
  accessToken?: string
  refreshToken?: string
  tokenType?: string
  expiresIn?: string
  refreshExpiresIn?: string
  customerId?: string
  tenantId?: string
  newRegister?: boolean
}

export interface RCustomerLoginView {
  code?: number
  message?: string
  data?: CustomerLoginView
  traceId?: string
  timestamp?: string
}

export interface RWalletView {
  code?: number
  message?: string
  data?: WalletView
  traceId?: string
  timestamp?: string
}

export interface WalletView {
  points?: string
  frozenPoints?: string
  depositPoints?: string
  arrearsPoints?: string
  unpaidOrders?: string
  activeOrders?: string
}

export interface RListSiteView {
  code?: number
  message?: string
  data?: SiteView[]
  traceId?: string
  timestamp?: string
}

export interface SiteView {
  siteCode?: string
  name?: string
  category?: string
  address?: string
  cabinetCount?: string
  freeSmall?: string
  freeMedium?: string
  freeLarge?: string
}

export interface CabinetView {
  cabinetNo?: string
  name?: string
  status?: string
  onlineState?: string
}

export interface RListCabinetView {
  code?: number
  message?: string
  data?: CabinetView[]
  traceId?: string
  timestamp?: string
}

export interface OrderView {
  orderNo?: string
  cabinetNo?: string
  slotNo?: string
  sizeType?: string
  status?: string
  startedAt?: string
  expectedFinishAt?: string
  createTime?: string
  settledPoints?: string
  arrearsPoints?: string
  leftoverRetrievable?: boolean
}

export interface RListOrderView {
  code?: number
  message?: string
  data?: OrderView[]
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

export interface RBillView {
  code?: number
  message?: string
  data?: BillView
  traceId?: string
  timestamp?: string
}

export interface ClosableView {
  cabinetNo?: string
  slotNo?: string
  sizeType?: string
}

export interface RListClosableView {
  code?: number
  message?: string
  data?: ClosableView[]
  traceId?: string
  timestamp?: string
}

export interface Availability {
  cabinetNo?: string
  sizes?: SizeFree[]
}

export interface RAvailability {
  code?: number
  message?: string
  data?: Availability
  traceId?: string
  timestamp?: string
}

export interface SizeFree {
  sizeType?: string
  free?: string
}

export interface RListView {
  code?: number
  message?: string
  data?: View[]
  traceId?: string
  timestamp?: string
}

export interface View {
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

export interface CustomerView {
  customerId?: string
  tenantId?: string
  nickname?: string
  phone?: string
  status?: number
  registerTime?: string
  lastLoginAt?: string
}

export interface RCustomerView {
  code?: number
  message?: string
  data?: CustomerView
  traceId?: string
  timestamp?: string
}

/** 接口操作表：key 为 "method path"（method 小写），入参与出参类型来自契约 */
export interface OperationTypes {
  "post /api/mini/support/chat": {
    summary: "客服对话"
    req: ChatRequest
    res: RAgentReply
  }
  "get /api/mini/storage/orders": {
    summary: "我的寄存单"
    req: null
    res: RListOrderView
  }
  "post /api/mini/storage/orders": {
    summary: "存件（下单并开门）"
    req: CreateOrderCommand
    res: RStorageOrderView
  }
  "post /api/mini/storage/orders/{orderNo}/voucher/reset": {
    summary: "重置取件码"
    req: null
    res: RVoucherView
  }
  "post /api/mini/storage/orders/{orderNo}/report-leftover": {
    summary: "上报格口内有他人遗留物"
    req: null
    res: RStorageOrderView
  }
  "post /api/mini/storage/orders/{orderNo}/pickup": {
    summary: "取件结算"
    req: PickupRequest
    res: RStorageOrderView
  }
  "post /api/mini/storage/orders/{orderNo}/leftover/taken": {
    summary: "我取完了"
    req: null
    res: RString
  }
  "post /api/mini/storage/orders/{orderNo}/leftover/open": {
    summary: "开门取物"
    req: null
    res: RString
  }
  "post /api/mini/storage/orders/{orderNo}/door": {
    summary: "开柜/关门校验"
    req: null
    res: RStorageOrderView
  }
  "post /api/mini/storage/cabinets/{cabinetNo}/slots/{slotNo}/closed-by-helper": {
    summary: "我帮他把门关上了"
    req: null
    res: RString
  }
  "post /api/mini/points/recharge": {
    summary: "充值点数"
    req: RechargeCommand
    res: RRechargeView
  }
  "post /api/mini/notices/{id}/read": {
    summary: "标记已读"
    req: null
    res: RVoid
  }
  "post /api/mini/auth/refresh": {
    summary: "刷新凭证"
    req: MiniRefreshRequest
    res: RMiniTokenView
  }
  "post /api/mini/auth/logout": {
    summary: "注销"
    req: MiniRefreshRequest
    res: RVoid
  }
  "post /api/mini/auth/login": {
    summary: "客户端登录"
    req: CustomerLoginRequest
    res: RCustomerLoginView
  }
  "get /api/mini/storage/wallet": {
    summary: "我的钱包"
    req: null
    res: RWalletView
  }
  "get /api/mini/storage/sites": {
    summary: "点位列表"
    req: null
    res: RListSiteView
  }
  "get /api/mini/storage/sites/{siteCode}/cabinets": {
    summary: "点位下的暂存柜"
    req: null
    res: RListCabinetView
  }
  "get /api/mini/storage/orders/{orderNo}/voucher": {
    summary: "取件凭据"
    req: null
    res: RVoucherView
  }
  "get /api/mini/storage/orders/{orderNo}/bill": {
    summary: "一张单的账单明细"
    req: null
    res: RBillView
  }
  "get /api/mini/storage/cabinets/{cabinetNo}/closable-slots": {
    summary: "这台暂存柜上只差关门的格口"
    req: null
    res: RListClosableView
  }
  "get /api/mini/storage/cabinets/{cabinetNo}/availability": {
    summary: "这台暂存柜各尺寸剩余可用格口"
    req: null
    res: RAvailability
  }
  "get /api/mini/notices": {
    summary: "我的消息"
    req: null
    res: RListView
  }
  "get /api/mini/auth/me": {
    summary: "当前客户信息"
    req: null
    res: RCustomerView
  }
}

export type OperationKey = keyof OperationTypes

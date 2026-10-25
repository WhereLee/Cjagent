// C 端接口层：只消费后端既有路由（/api/mini/** 与 /api/public/storage/pickup），
// 不新增字段与概念。类型以 schema-mini.ts（生成物）为准；撞名丢失的见 contracts.ts。
import { request } from './http'
import type {
  AgentReply,
  Availability,
  BillView,
  CabinetView,
  ClosableView,
  CreateOrderCommand,
  CustomerLoginRequest,
  CustomerLoginView,
  CustomerView,
  MiniRefreshRequest,
  MiniTokenView,
  OrderView,
  RechargeCommand,
  RechargeView,
  SiteView,
  StorageOrderView,
  VoucherView,
  NoticeView,
} from './schema-mini'
import type { PublicPickupRequest, WorkOrderConfirmRequest, WorkOrderCustomerView } from './contracts'

// ── 认证 /api/mini/auth ───────────────────────────────────────────
// dev 下 mock 登录接受任意 code；首次注册必须带 tenantCode 与 phone（11 位、1 开头）
export const miniLogin = (body: CustomerLoginRequest) =>
  request<CustomerLoginView>({ url: '/api/mini/auth/login', method: 'POST', data: body })

export const miniRefresh = (body: MiniRefreshRequest) =>
  request<MiniTokenView>({ url: '/api/mini/auth/refresh', method: 'POST', data: body })

export const miniLogout = (body: MiniRefreshRequest) =>
  request<void>({ url: '/api/mini/auth/logout', method: 'POST', data: body })

export const miniMe = () => request<CustomerView>({ url: '/api/mini/auth/me' })

// ── 查询 /api/mini/storage ────────────────────────────────────────
export const fetchSites = () => request<SiteView[]>({ url: '/api/mini/storage/sites' })

export const fetchSiteCabinets = (siteCode: string) =>
  request<CabinetView[]>({ url: `/api/mini/storage/sites/${siteCode}/cabinets` })

export const fetchAvailability = (cabinetNo: string) =>
  request<Availability>({ url: `/api/mini/storage/cabinets/${cabinetNo}/availability` })

export const fetchMyOrders = (limit = 20) =>
  request<OrderView[]>({ url: '/api/mini/storage/orders', params: { limit } })

export const fetchBill = (orderNo: string) =>
  request<BillView>({ url: `/api/mini/storage/orders/${orderNo}/bill` })

export const fetchWallet = () => request<{ points?: string; frozenPoints?: string; depositPoints?: string; arrearsPoints?: string; unpaidOrders?: string; activeOrders?: string }>({
  url: '/api/mini/storage/wallet',
})

// ── 下单与开柜动作 ────────────────────────────────────────────────
/** 一步完成占格 + 预收 + 开门。requestId 是客户端幂等键（uuid），重复提交返回首次结果 */
export const createOrder = (body: CreateOrderCommand) =>
  request<StorageOrderView>({ url: '/api/mini/storage/orders', method: 'POST', data: body })

/** 动作三选：OPEN（投件开柜）/ OPEN_TEMP（中途取物重开）/ CLOSE_VERIFY（关门校验） */
export const doorAction = (orderNo: string, action: 'OPEN' | 'OPEN_TEMP' | 'CLOSE_VERIFY') =>
  request<StorageOrderView>({
    url: `/api/mini/storage/orders/${orderNo}/door`,
    method: 'POST',
    params: { action },
  })

/** 结算 + 取物。登录态主路径不需要码，voucherCode 可省 */
export const pickup = (orderNo: string, voucherCode?: string) =>
  request<StorageOrderView>({
    url: `/api/mini/storage/orders/${orderNo}/pickup`,
    method: 'POST',
    data: voucherCode ? { voucherCode } : {},
  })

/** 上报格口内有他人遗留物：本单免费退回、格口锁定 */
export const reportLeftover = (orderNo: string) =>
  request<StorageOrderView>({ url: `/api/mini/storage/orders/${orderNo}/report-leftover`, method: 'POST' })

// ── 遗留物自助闭环 ────────────────────────────────────────────────
/** 开门取物（遗留物取回） */
export const leftoverOpen = (orderNo: string) =>
  request<string>({ url: `/api/mini/storage/orders/${orderNo}/leftover/open`, method: 'POST' })

/** 我取完了 */
export const leftoverTaken = (orderNo: string) =>
  request<string>({ url: `/api/mini/storage/orders/${orderNo}/leftover/taken`, method: 'POST' })

/** 这台柜机上只差关门的格口（帮忙关门前先看清单） */
export const fetchClosableSlots = (cabinetNo: string) =>
  request<ClosableView[]>({ url: `/api/mini/storage/cabinets/${cabinetNo}/closable-slots` })

/** 我帮他把门关上了 */
export const closedByHelper = (cabinetNo: string, slotNo: string) =>
  request<string>({ url: `/api/mini/storage/cabinets/${cabinetNo}/slots/${slotNo}/closed-by-helper`, method: 'POST' })

// ── 取件凭据（第 25 刀凭据闭环）────────────────────────────────────
export const fetchVoucher = (orderNo: string) =>
  request<VoucherView>({ url: `/api/mini/storage/orders/${orderNo}/voucher` })

/** 换码：旧码立即失效、错次清零、连错锁定一并解除 */
export const resetVoucher = (orderNo: string) =>
  request<VoucherView>({ url: `/api/mini/storage/orders/${orderNo}/voucher/reset`, method: 'POST' })

/** 匿名双因子凭码取件（手机不在手上时的第二条出路），按 IP 限流 */
export const pickupByVoucher = (body: PublicPickupRequest) =>
  request<StorageOrderView>({
    url: '/api/public/storage/pickup',
    method: 'POST',
    data: body,
    anonymous: true,
  })

// ── 充值 / 客服 / 消息 ────────────────────────────────────────────
/** 1 点 = 1 分；正整数；单笔上限 100000 点 */
export const recharge = (body: RechargeCommand) =>
  request<RechargeView>({ url: '/api/mini/points/recharge', method: 'POST', data: body })

/** AI 客服（message ≤ 500 字）；escalated=true 时前端切"已建单"态展示 ticketNo */
export const supportChat = (body: { orderNo?: string; message: string }) =>
  request<AgentReply>({
    url: '/api/mini/support/chat',
    method: 'POST',
    data: body,
  })

export const fetchNotices = () => request<NoticeView[]>({ url: '/api/mini/notices' })

export const readNotice = (id: string) =>
  request<void>({ url: `/api/mini/notices/${id}/read`, method: 'POST' })

// ── 我的工单 / 确认闭环 ───────────────────────────────────────────
export const fetchMyWorkOrders = (limit = 20) =>
  request<WorkOrderCustomerView[]>({ url: '/api/mini/work-orders', params: { limit } })

export const fetchWorkOrder = (id: string) =>
  request<WorkOrderCustomerView>({ url: `/api/mini/work-orders/${id}` })

/** solved=true 确认已解决；false 反馈还没解决（重开本人已关闭的单） */
export const confirmWorkOrder = (id: string, body: WorkOrderConfirmRequest) =>
  request<string>({ url: `/api/mini/work-orders/${id}/confirm`, method: 'POST', data: body })

/** 确认解决后的一次性满意度评价（1–5 星，备注选填） */
export const submitWorkOrderCsat = (id: string, body: { score: number; comment?: string }) =>
  request<{ workOrderId?: string; score?: number; comment?: string; at?: string }>({
    url: `/api/mini/work-orders/${id}/csat`,
    method: 'POST',
    data: body,
  })

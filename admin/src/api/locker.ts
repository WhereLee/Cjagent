import { request } from '@/api/http'
import type { PageQuery, PageResult } from '@/types/api'

/**
 * 后台-储物柜现场运营的接口层（第 14E 刀）。
 *
 * 类型按后端 `LockerConsoleService` 的视图记录对齐。<b>ID 一律写成 string | number</b>：
 * 底座把雪花 ID 全局序列化成字符串（防 JS 精度丢失），而停留时长这类是数字。
 * 这里统一收宽，比在页面上到处 `Number(...)` 更安全——展示不 care 类型，运算才需要转。
 */
export type IdValue = string | number

export interface LedgerView {
  compartmentId: IdValue
  cabinetId: IdValue
  cabinetNo: string
  slotNo: string
  /** 异常原因码：DOOR_OPEN / CONTENT_LEFT / CONTENT_UNVERIFIED / SENSOR_CONFLICT */
  anomaly: string
  anomalyAt?: string
  /** 卡了多久（分钟）。排班的依据是它，不是创建时间 */
  stuckMinutes?: number
  anomalyReason?: string
  siteId?: IdValue
  siteName?: string
  currentOrderId?: IdValue
}

export interface SummaryView {
  anomaly: string
  rowsCount: number
  longestMinutes: number
}

export interface DepositView {
  depositId: IdValue
  orderNo: string
  customerId: IdValue
  points: number
  depositStatus: string
  heldHours?: number
}

export interface ArrearsView {
  customerId: IdValue
  arrearsPoints: number
  arrearsOrders: number
  lastFinishedAt?: string
}

export interface EvidenceRow {
  id: IdValue
  orderId?: IdValue
  source: string
  presence: string
  confidence?: number
  note?: string
  createTime?: string
}

export interface CompartmentDetail {
  compartment: {
    id: IdValue
    cabinetId: IdValue
    slotNo: string
    status: string
    currentOrderId?: IdValue
    doorOpenAt?: string
    doorClosedAt?: string
    presence?: string
    presenceCheckedAt?: string
    anomaly?: string
    anomalyAt?: string
    anomalyReason?: string
  }
  evidence: EvidenceRow[]
  currentOrder?: { orderNo: string; status: string; customerId?: IdValue }
}

/** 与后端 anomaly 枚举对齐，下拉框直接用这份而不是页面里硬写字符串 */
export const ANOMALY_OPTIONS = [
  { value: 'DOOR_OPEN', label: '柜门未关' },
  { value: 'CONTENT_LEFT', label: '遗留物品' },
  { value: 'CONTENT_UNVERIFIED', label: '柜内待确认' },
  { value: 'SENSOR_CONFLICT', label: '传感器矛盾' },
] as const

export const ANOMALY_LABEL: Record<string, string> = Object.fromEntries(
  ANOMALY_OPTIONS.map((item) => [item.value, item.label]),
)

export interface LedgerQuery extends PageQuery {
  anomaly?: string
  siteId?: number
}

export function listAnomalies(query: LedgerQuery) {
  return request<PageResult<LedgerView>>({
    url: '/api/admin/locker/compartments/anomalies',
    method: 'get',
    params: query,
  })
}

export function summarizeAnomalies(siteId?: number) {
  return request<SummaryView[]>({
    url: '/api/admin/locker/compartments/anomalies/summary',
    method: 'get',
    params: { siteId },
  })
}

export function compartmentDetail(compartmentId: IdValue) {
  return request<CompartmentDetail>({
    url: `/api/admin/locker/compartments/${compartmentId}`,
    method: 'get',
  })
}

export function listUnrefundedDeposits(query: PageQuery) {
  return request<PageResult<DepositView>>({
    url: '/api/admin/locker/deposits/unrefunded',
    method: 'get',
    params: query,
  })
}

export function listArrears(query: PageQuery) {
  return request<PageResult<ArrearsView>>({
    url: '/api/admin/locker/arrears',
    method: 'get',
    params: query,
  })
}

// ------------------------------------------------------------------ 处置动作（每个一条权限点）

export function resolveAnomaly(compartmentId: IdValue, note: string) {
  return request<void>({
    url: `/api/admin/locker/compartments/${compartmentId}/resolve`,
    method: 'post',
    data: { note },
  })
}

/**
 * 强制开柜。`confirmed` 是后端硬要求（false 直接 10000），不是前端复选框装饰：
 * 这个动作开的是可能留着别人物品的格子，必须留下一次显式确认的痕迹。
 */
export function forceOpen(compartmentId: IdValue, reason: string) {
  return request<void>({
    url: `/api/admin/locker/compartments/${compartmentId}/force-open`,
    method: 'post',
    data: { confirmed: true, reason },
  })
}

/**
 * 免除争议费用。<b>没有金额参数</b>：免除多少由后端按「计费起点 → 争议起点」算，
 * 前端能填金额就等于把定价权交给了点按钮的人。
 */
export function waiveDisputeFee(orderNo: string) {
  return request<void>({
    url: `/api/admin/locker/orders/${orderNo}/waive-fee`,
    method: 'post',
  })
}

export function changeCustomerStatus(customerId: IdValue, status: 0 | 1, reason: string) {
  return request<void>({
    url: `/api/admin/locker/customers/${customerId}/status`,
    method: 'post',
    data: { status, reason },
  })
}

export function rebuildFreeSet(cabinetId: IdValue) {
  return request<void>({
    url: `/api/admin/locker/cabinets/${cabinetId}/free-set/rebuild`,
    method: 'post',
  })
}

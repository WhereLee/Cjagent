/**
 * 展示字典：后端枚举 → 中文。
 *
 * 只做翻译与色调映射，不新增任何状态或规则；未知值原样展示，不吞不崩。
 * 中文口径逐条对应后端枚举注释（OrderStatus / CompartmentAnomaly / PointTxnType 等），
 * 后端新增枚举时这里补一行即可，不允许在页面里另写一套。
 */

export type Tone = 'info' | 'primary' | 'success' | 'warning' | 'danger'

const ORDER_STATUS_TEXT: Record<string, string> = {
  INIT: '待分配格口',
  RESERVED: '待开柜',
  OPENING: '正在开门',
  STORED: '已存件',
  ACTIVE: '计费中',
  TEMP_OPEN: '临时开门',
  SETTLING: '结算中',
  CLOSED: '已完成',
  CANCELLED: '已取消',
  ABNORMAL: '订单异常',
  EXPIRED: '已逾期',
}

const ORDER_STATUS_TONE: Record<string, Tone> = {
  INIT: 'info',
  RESERVED: 'warning',
  OPENING: 'warning',
  STORED: 'primary',
  ACTIVE: 'primary',
  TEMP_OPEN: 'warning',
  SETTLING: 'warning',
  CLOSED: 'success',
  CANCELLED: 'info',
  ABNORMAL: 'danger',
  EXPIRED: 'danger',
}

/** 状态说明：用户此刻该知道的一句话，来源同 OrderStatus 注释 */
const ORDER_STATUS_HINT: Record<string, string> = {
  INIT: '正在为你分配格口，请稍候',
  RESERVED: '柜门还没有打开，可以重新开门',
  OPENING: '开门指令已下发，请稍候',
  STORED: '物品已放入，等待计费开始',
  ACTIVE: '存放计时中，取件时按实际时长结算，多退少补',
  TEMP_OPEN: '柜门临时打开中，关好后可继续存放',
  SETTLING: '正在结算，请稍候',
  CLOSED: '订单已完成',
  CANCELLED: '订单已取消，预收费用已退回',
  ABNORMAL: '订单状态异常，请联系客服处理',
  EXPIRED: '已到封顶时长，仍可取件结算',
}

const NOTICE_KIND_TEXT: Record<string, string> = {
  DOOR_NOT_CLOSED: '门未关提醒',
  ITEM_LEFT: '遗留物处理',
  DEVICE_FAULT: '设备故障',
  LONG_RUNNING: '超时未取',
  LEFTOVER_OWNER: '遗留物待取回',
  PICKUP_VOUCHER: '取件码',
  WORK_ORDER_AUTO_CLOSED: '工单自动关闭',
}

const NOTICE_CHANNEL_TEXT: Record<string, string> = {
  SYSTEM_MESSAGE: '站内消息',
  PHONE_CALL: '电话任务',
}

const BILL_LINE_TEXT: Record<string, string> = {
  RECHARGE: '充值',
  ADJUST: '人工调整',
  CONSUME: '消费扣款',
  FREEZE_OUT: '冻结转出',
  FREEZE_IN: '冻结转入',
  UNFREEZE_OUT: '解冻转出',
  UNFREEZE_IN: '解冻转入',
  DEPOSIT_OUT: '划出押金',
  DEPOSIT_IN: '押金入账',
  DEPOSIT_BACK_OUT: '押金退还转出',
  DEPOSIT_BACK_IN: '押金退还入账',
  PREPAY: '下单预收',
  PREPAY_REFUND: '预收退还',
}

const CABINET_STATUS_TEXT: Record<string, string> = {
  ENABLED: '正常营业',
  DISABLED: '人工停用',
  FAULT: '故障停用',
}

const ONLINE_STATE_TEXT: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  UNKNOWN: '在线状态未上报',
}

/** 工单状态（后端 WorkOrderStatus）：用户只看“在处理 / 办完了”。 */
const WORK_ORDER_STATUS_TEXT: Record<string, string> = {
  PENDING: '待处理',
  CLAIMED: '处理中',
  RESOLVED: '已解决',
  AUTO_CLOSED: '已自动关闭',
  CANCELLED: '已取消',
}

const WORK_ORDER_STATUS_TONE: Record<string, Tone> = {
  PENDING: 'warning',
  CLAIMED: 'primary',
  RESOLVED: 'success',
  AUTO_CLOSED: 'info',
  CANCELLED: 'info',
}

/** 处置分类（后端 WorkOrderCategory） */
const WORK_ORDER_CATEGORY_TEXT: Record<string, string> = {
  DOOR_FAULT: '开关门故障',
  DOOR_NOT_CLOSED: '门未关',
  ITEM_DISPUTE: '物品争议',
  ITEM_LEFT: '遗留物',
  DEVICE_OFFLINE: '设备离线',
  BILLING_DISPUTE: '费用争议',
  PICKUP_CODE: '取件码问题',
  UNSORTED: '待人工分拣',
}

const WORK_ORDER_PRIORITY_TEXT: Record<string, string> = {
  P1: 'P1 立即',
  P2: 'P2 紧急',
  P3: 'P3 普通',
  P4: 'P4 可等',
}

const CLOSE_REASON_TEXT: Record<string, string> = {
  MANUAL: '人工处理完成',
  FACT_RECOVERED: '异常已解除',
  USER_CONFIRMED: '你已确认解决',
  MERGED: '已合并到处理中的工单',
  CANCELLED: '已作废',
}

export function orderStatusText(value?: string | null): string {
  return value ? (ORDER_STATUS_TEXT[value] ?? value) : '—'
}

export function orderStatusTone(value?: string | null): Tone {
  return (value && ORDER_STATUS_TONE[value]) || 'info'
}

export function orderStatusHint(value?: string | null): string {
  return (value && ORDER_STATUS_HINT[value]) || ''
}

/** 活单：还占着格口、还能操作。判据只有终态两种，与 OrderStatus.isTerminal 一致 */
export function isOrderActive(value?: string | null): boolean {
  return Boolean(value) && value !== 'CLOSED' && value !== 'CANCELLED'
}

export function noticeKindText(value?: string | null): string {
  return value ? (NOTICE_KIND_TEXT[value] ?? value) : '—'
}

export function noticeChannelText(value?: string | null): string {
  return value ? (NOTICE_CHANNEL_TEXT[value] ?? value) : '—'
}

export function billLineTypeText(value?: string | null): string {
  return value ? (BILL_LINE_TEXT[value] ?? value) : '—'
}

export function cabinetStatusText(value?: string | null): string {
  return value ? (CABINET_STATUS_TEXT[value] ?? value) : '—'
}

export function onlineStateText(value?: string | null): string {
  return value ? (ONLINE_STATE_TEXT[value] ?? value) : '—'
}

export function workOrderStatusText(value?: string | null): string {
  return value ? (WORK_ORDER_STATUS_TEXT[value] ?? value) : '—'
}

export function workOrderStatusTone(value?: string | null): Tone {
  return (value && WORK_ORDER_STATUS_TONE[value]) || 'info'
}

/** 还没结束：用户可以确认“已解决” */
export function isWorkOrderOpen(value?: string | null): boolean {
  return value === 'PENDING' || value === 'CLAIMED'
}

/** 已结束：用户可以反馈“还没解决”重开 */
export function canReopenWorkOrder(value?: string | null): boolean {
  return value === 'RESOLVED' || value === 'AUTO_CLOSED'
}

export function workOrderCategoryText(value?: string | null): string {
  return value ? (WORK_ORDER_CATEGORY_TEXT[value] ?? value) : '—'
}

export function workOrderPriorityText(value?: string | null): string {
  return value ? (WORK_ORDER_PRIORITY_TEXT[value] ?? value) : '—'
}

export function closeReasonText(value?: string | null): string {
  return value ? (CLOSE_REASON_TEXT[value] ?? value) : '—'
}

/**
 * 柜机当前能不能接新单。只看业务开关 CabinetStatus：
 * 在线状态是允许滞后的快照，后端明确它不参与实时判定，前端更不能拿它拦人。
 */
export function cabinetAcceptingNewOrders(status?: string | null): boolean {
  return status === 'ENABLED' || !status
}

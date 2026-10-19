/**
 * 展示字典：后端枚举 → 中文 + 标签色调。
 *
 * 只做翻译与展示映射，不新增状态或规则；未知值原样展示，不吞不崩。
 * 中文口径逐条对应后端枚举注释（WorkOrderType / CompartmentAnomaly / PointTxnType 等）。
 */

export type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

function pick(map: Record<string, string>, value?: string | null): string {
  return value ? (map[value] ?? value) : '—'
}

// ── 寄存单 ───────────────────────────────────────────
const ORDER_STATUS: Record<string, string> = {
  INIT: '待分配格口',
  RESERVED: '待开柜',
  OPENING: '正在开门',
  STORED: '已存件',
  ACTIVE: '计费中',
  TEMP_OPEN: '临时开门',
  SETTLING: '结算中',
  CLOSED: '已完成',
  CANCELLED: '已取消',
  ABNORMAL: '异常',
  EXPIRED: '已逾期',
}

// ── 工单 ─────────────────────────────────────────────
const WORK_ORDER_TYPE: Record<string, string> = {
  DOOR_NOT_CLOSED: '门未关',
  ITEM_LEFT: '遗留物',
  DEVICE_FAULT: '开关门故障',
  LONG_RUNNING: '超时未取',
}

const WORK_ORDER_STATUS: Record<string, string> = {
  PENDING: '待认领',
  CLAIMED: '已认领',
  RESOLVED: '已处理',
  AUTO_CLOSED: '自动关闭',
  CANCELLED: '已作废',
}

const WORK_ORDER_CATEGORY: Record<string, string> = {
  DOOR_FAULT: '开关门故障',
  DOOR_NOT_CLOSED: '门未关',
  ITEM_DISPUTE: '物品争议',
  ITEM_LEFT: '遗留物',
  DEVICE_OFFLINE: '设备离线',
  BILLING_DISPUTE: '费用争议',
  PICKUP_CODE: '取件码问题',
  UNSORTED: '待人工分拣',
}

const WORK_ORDER_PRIORITY: Record<string, string> = {
  P1: 'P1 立即',
  P2: 'P2 紧急',
  P3: 'P3 普通',
  P4: 'P4 可等',
}

const WORK_ORDER_PRIORITY_TAG: Record<string, TagType> = {
  P1: 'danger',
  P2: 'warning',
  P3: 'primary',
  P4: 'info',
}

const ASSIGNEE_GROUP: Record<string, string> = {
  ONLINE: '线上业务员',
  FIELD: '线下运维',
  FIELD_OPS: '现场运维',
  CUSTOMER_SERVICE: '人工分拣',
}

const WORK_ORDER_STATUS_TAG: Record<string, TagType> = {
  PENDING: 'danger',
  CLAIMED: 'warning',
  RESOLVED: 'success',
  AUTO_CLOSED: 'info',
  CANCELLED: 'info',
}

const QUEUE_SIDE: Record<string, string> = {
  ONLINE: '线上',
  FIELD: '线下',
}

const WORK_ORDER_EVENT: Record<string, string> = {
  CREATED: '开单',
  REPEATED: '重复观察',
  CLAIMED: '认领',
  RESOLVED: '处理反馈',
  DISPATCHED: '派发线下',
  AUTO_CLOSED: '自动关闭',
  PHONE_CALLED: '电话留痕',
  REMINDER: '提醒',
  ESCALATED: '升级处理',
  TRIAGED: '重新分诊',
  SLA_PAUSED: '暂停SLA',
  SLA_RESUMED: '恢复SLA',
  HELPER_CLOSED: '他人代关',
  USER_CONFIRMED: '用户确认解决',
  REOPENED: '用户重开',
}

const CLOSE_REASON: Record<string, string> = {
  MANUAL: '人工结论',
  FACT_RECOVERED: '事实恢复',
  USER_CONFIRMED: '用户确认',
  MERGED: '重复合并',
  CANCELLED: '作废',
}

// ── 格口与证据 ────────────────────────────────────────
const COMPARTMENT_ANOMALY: Record<string, string> = {
  DOOR_OPEN: '门未关',
  CONTENT_LEFT: '有遗留物',
  CONTENT_UNVERIFIED: '物品未核实',
  SENSOR_CONFLICT: '传感器矛盾',
}

const COMPARTMENT_ANOMALY_TAG: Record<string, TagType> = {
  DOOR_OPEN: 'warning',
  CONTENT_LEFT: 'danger',
  CONTENT_UNVERIFIED: 'warning',
  SENSOR_CONFLICT: 'danger',
}

const SLOT_STATUS: Record<string, string> = {
  FREE: '空闲',
  RESERVED: '已预占',
  OCCUPIED: '占用中',
  FAULT: '故障',
  MAINTENANCE: '人工维护',
}

const PRESENCE: Record<string, string> = {
  PRESENT: '有物',
  ABSENT: '无物',
  UNKNOWN: '测不到',
}

const EVIDENCE_SOURCE: Record<string, string> = {
  INFRARED: '红外物检',
  DEVICE_PROBE: '设备探测',
  AI: 'AI 识图',
}

const EVIDENCE_MOMENT: Record<string, string> = {
  DOOR_OPENED: '开门时',
  DOOR_CLOSED: '关门时',
  WATCH_DETECT: '看管检测',
  HELPER_CLOSED: '他人代关后',
}

// ── 资金与定价 ────────────────────────────────────────
const DEPOSIT_STATUS: Record<string, string> = {
  HELD: '持有中',
  REFUNDING: '退款中',
  REFUNDED: '已退还',
  FAILED: '退款失败',
}

const DEPOSIT_STATUS_TAG: Record<string, TagType> = {
  HELD: 'warning',
  REFUNDING: 'primary',
  REFUNDED: 'success',
  FAILED: 'danger',
}

const PRICE_RULE_STATUS: Record<string, string> = {
  LIVE: '生效中',
  SUPERSEDED: '已被取代',
}

const POINT_TXN_TYPE: Record<string, string> = {
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

// ── 柜机 ─────────────────────────────────────────────
const CABINET_STATUS: Record<string, string> = {
  ENABLED: '正常营业',
  DISABLED: '人工停用',
  FAULT: '故障停用',
}

const ONLINE_STATE: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  UNKNOWN: '未知',
}

// ── 通知 ─────────────────────────────────────────────
const NOTICE_KIND: Record<string, string> = {
  DOOR_NOT_CLOSED: '门未关',
  ITEM_LEFT: '遗留物',
  DEVICE_FAULT: '设备故障',
  LONG_RUNNING: '超时未取',
  LEFTOVER_OWNER: '遗留物待取回',
  PICKUP_VOUCHER: '取件码',
}

const NOTICE_CHANNEL: Record<string, string> = {
  SYSTEM_MESSAGE: '站内消息',
  PHONE_CALL: '电话任务',
}

export const workOrderTypeText = (v?: string | null) => pick(WORK_ORDER_TYPE, v)
export const orderStatusText = (v?: string | null) => pick(ORDER_STATUS, v)
export const workOrderStatusText = (v?: string | null) => pick(WORK_ORDER_STATUS, v)
export const workOrderStatusTag = (v?: string | null): TagType =>
  (v && WORK_ORDER_STATUS_TAG[v]) || 'info'
export const queueSideText = (v?: string | null) => pick(QUEUE_SIDE, v)
export const workOrderCategoryText = (v?: string | null) => pick(WORK_ORDER_CATEGORY, v)
export const workOrderPriorityText = (v?: string | null) => pick(WORK_ORDER_PRIORITY, v)
export const workOrderPriorityTag = (v?: string | null): TagType =>
  (v && WORK_ORDER_PRIORITY_TAG[v]) || 'info'
export const assigneeGroupText = (v?: string | null) => pick(ASSIGNEE_GROUP, v)
export const workOrderEventText = (v?: string | null) => pick(WORK_ORDER_EVENT, v)
export const closeReasonText = (v?: string | null) => pick(CLOSE_REASON, v)

export const anomalyText = (v?: string | null) => pick(COMPARTMENT_ANOMALY, v)
export const anomalyTag = (v?: string | null): TagType => (v && COMPARTMENT_ANOMALY_TAG[v]) || 'info'
export const slotStatusText = (v?: string | null) => pick(SLOT_STATUS, v)
export const presenceText = (v?: string | null) => pick(PRESENCE, v)
export const evidenceSourceText = (v?: string | null) => pick(EVIDENCE_SOURCE, v)
export const evidenceMomentText = (v?: string | null) => pick(EVIDENCE_MOMENT, v)

export const depositStatusText = (v?: string | null) => pick(DEPOSIT_STATUS, v)
export const depositStatusTag = (v?: string | null): TagType =>
  (v && DEPOSIT_STATUS_TAG[v]) || 'info'
export const priceRuleStatusText = (v?: string | null) => pick(PRICE_RULE_STATUS, v)
export const pointTxnTypeText = (v?: string | null) => pick(POINT_TXN_TYPE, v)

export const cabinetStatusText = (v?: string | null) => pick(CABINET_STATUS, v)
export const onlineStateText = (v?: string | null) => pick(ONLINE_STATE, v)

export const noticeKindText = (v?: string | null) => pick(NOTICE_KIND, v)
export const noticeChannelText = (v?: string | null) => pick(NOTICE_CHANNEL, v)

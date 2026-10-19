/**
 * 展示层格式化（跨端）。
 * 1. ID 与 Long 按字符串展示（wire 上就是字符串），不做 parseInt/Number 还原。
 * 2. 点数 = 分（1 点 = 1 分），换算元用字符串拼接，不在浮点上算钱。
 */

/** 点数（分）→ "x.xx 元" */
export function pointsToYuan(points: string | number | null | undefined): string {
  if (points === null || points === undefined || points === '') return '—'
  const neg = String(points).startsWith('-')
  const digits = String(points).replace('-', '').padStart(3, '0')
  const yuan = digits.slice(0, -2) || '0'
  const cents = digits.slice(-2)
  return `${neg ? '-' : ''}${yuan}.${cents}`
}

/** 时间展示：ISO 的 T 换空格，只保留到分钟 */
export function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\.\d+$/, '').slice(0, 16)
}

/** 状态原样展示：未知枚举值不吞不崩 */
export function fmtStatus(status: string | null | undefined): string {
  return status ?? '—'
}

/** 尺寸中文名；未知值原样返回 */
export function fmtSize(size: string | null | undefined): string {
  const map: Record<string, string> = { SMALL: '小格', MEDIUM: '中格', LARGE: '大格' }
  return size ? (map[size] ?? size) : '—'
}

/** uuid 幂等键：同一"用户意图"重试复用同一个，新意图换新。小程序端没有 crypto 时兜底 */
export function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `rid-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 已存放时长（展示用）。
 * 不是计费判据：真实结算以服务端按计价快照算出的账单为准，这里只帮用户感知“放了多久”。
 */
export function elapsedText(from: string | null | undefined): string {
  if (!from) return '—'
  const start = new Date(from.replace(' ', 'T')).getTime()
  if (!Number.isFinite(start)) return '—'
  const minutes = Math.max(0, Math.floor((Date.now() - start) / 60000))
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}

/** 统一错误提示：优先展示后端 message */
export function toastError(e: unknown): void {
  const message = e instanceof Error && e.message ? e.message : '操作失败，请稍后重试'
  uni.showToast({ title: message, icon: 'none', duration: 2500 })
}

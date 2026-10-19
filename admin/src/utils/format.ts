/**
 * 展示层格式化。两条口径：
 * 1. ID 与 Long 一律按字符串展示（wire 上就是字符串），不做 parseInt/Number 还原。
 * 2. 点数以"分"为最小单位（1 点 = 1 分）：换算成元展示用整数运算，不在浮点上算钱。
 */

/** 点数（分）→ 元，字符串拼接避免浮点误差 */
export function pointsToYuan(points: string | number | null | undefined): string {
  if (points === null || points === undefined || points === '') return '—'
  const neg = String(points).startsWith('-')
  const digits = String(points).replace('-', '').padStart(3, '0')
  const yuan = digits.slice(0, -2) || '0'
  const cents = digits.slice(-2)
  return `${neg ? '-' : ''}${yuan}.${cents}`
}

/** 时间原样展示（后端统一 yyyy-MM-dd HH:mm:ss 或 ISO），空值给占位 */
export function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\.\d+$/, '')
}

/** 分钟数 → "x 小时 y 分" */
export function fmtMinutes(minutes: string | number | null | undefined): string {
  if (minutes === null || minutes === undefined || minutes === '') return '—'
  const total = Number(minutes)
  if (!Number.isFinite(total)) return String(minutes)
  const h = Math.floor(total / 60)
  const m = total % 60
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分`
}

/** 状态原样展示：前端不认识的枚举值不吞、不崩 */
export function fmtStatus(status: string | null | undefined): string {
  return status ?? '—'
}

/** uuid 做幂等键：同一"用户意图"重试复用同一个，新意图换新 */
export function newRequestId(): string {
  return crypto.randomUUID()
}

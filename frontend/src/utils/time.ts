/**
 * 北京时间（Asia/Shanghai）与后端 UTC 时间之间的转换。
 *
 * 后端请求要求带偏移的 ISO 8601，响应统一输出 UTC 的 `...Z` 形式。
 *
 * 这里**刻意不使用 new Date('2026-10-05T20:00:00') 这类解析**：不带偏移的字符串会按
 * 浏览器所在时区解释，机器一旦不在 UTC+8，界面上就会出现 8 小时偏差。
 * 中国自 1991 年起不再使用夏令时，Asia/Shanghai 全年固定 UTC+8，
 * 因此用固定偏移做换算与真实时区规则完全等价，且不依赖运行环境的时区数据。
 */

/** 北京时间固定偏移（分钟） */
const SHANGHAI_OFFSET_MINUTES = 8 * 60

/** 提交给后端时使用的固定偏移写法 */
export const SHANGHAI_OFFSET_SUFFIX = '+08:00'

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0')
}

/**
 * 后端 UTC 时间 → 北京时间墙上时钟（`YYYY-MM-DDTHH:mm:ss`），
 * 可直接绑定到 el-date-picker 的 value-format。
 *
 * 做法是先把时间戳整体平移 +8 小时，再用 getUTC* 读取，
 * 这样结果只取决于输入时刻，与浏览器系统时区无关。
 */
export function toShanghaiInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return ''

  const shifted = new Date(ms + SHANGHAI_OFFSET_MINUTES * 60_000)
  const date = `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`
  const time = `${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}:${pad(shifted.getUTCSeconds())}`
  return `${date}T${time}`
}

/**
 * 北京时间的墙上时钟字符串 → 带 `+08:00` 偏移的 ISO 8601，供提交。
 *
 * 表单里的值本来就是北京时间的墙上时钟，所以**原样**拼上偏移即可；
 * 空串返回 null（草稿允许开始时间为空）。
 */
export function fromShanghaiInput(local: string | null | undefined): string | null {
  const text = (local ?? '').trim()
  if (text === '') return null

  const normalized = text.replace(' ', 'T')
  // 只到分钟时补上秒，保证后端拿到的始终是完整时间
  const withSeconds = normalized.length === 16 ? `${normalized}:00` : normalized
  return `${withSeconds}${SHANGHAI_OFFSET_SUFFIX}`
}

/** 后端 UTC 时间 → 展示用的北京时间（`YYYY-MM-DD HH:mm`） */
export function formatShanghai(iso: string | null | undefined): string {
  const local = toShanghaiInput(iso)
  if (local === '') return ''
  return `${local.slice(0, 10)} ${local.slice(11, 16)}`
}

/** 后端 UTC 时间 → 展示用的北京时间（含秒，用于核对「同一时刻」） */
export function formatShanghaiWithSeconds(iso: string | null | undefined): string {
  const local = toShanghaiInput(iso)
  if (local === '') return ''
  return `${local.slice(0, 10)} ${local.slice(11, 19)}`
}

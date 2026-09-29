/**
 * 订单倒计时。
 *
 * 后端只给 expireAt 与 serverTime 两个 UTC 时刻，倒计时必须由前端推算。
 * 规则：
 * - 以本次响应里 serverTime 为基准，**不用本地墙上时钟**猜还剩多久；
 * - 从收到响应开始单调流逝的时间由调用方用 `performance.now()` 的差值传入
 *   `elapsedMs`（浏览器性能时钟在后台标签页仍会推进，符合「切后台再返回不漂移」）；
 * - 算出来的秒数只向下取整（ceil）后钳制到 ≥ 0，**不伪造任何状态**，
 *   归零时页面只提示并查询真实状态，由后端决定是否已关闭。
 */

/**
 * 剩余支付秒数。
 *
 * @param expireAt 支付截止时间（后端 UTC `...Z`）
 * @param serverTime 本次响应时刻（后端 UTC `...Z`）
 * @param elapsedMs 从收到该响应起单调流逝的毫秒数
 */
export function remainingSeconds(
  expireAt: string,
  serverTime: string,
  elapsedMs: number,
): number {
  const expire = Date.parse(expireAt)
  const server = Date.parse(serverTime)
  if (Number.isNaN(expire) || Number.isNaN(server)) return 0

  const spanMs = expire - server - Math.max(0, elapsedMs)
  return Math.max(0, Math.ceil(spanMs / 1000))
}

/** 把剩余秒数格式化成 mm:ss 或 h:mm:ss，用于展示 */
export function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  const hours = Math.floor(s / 3600)
  const minutes = Math.floor((s % 3600) / 60)
  const seconds = s % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(seconds).padStart(2, '0')
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`
}
/**
 * 金额与库存输入的解析。
 *
 * 后端 priceCent / totalStock 只接受 JSON **整数**，且关闭了 Jackson 的浮点转整数，
 * 所以 99.9 与 99.0 都会被 400 拒绝。前端必须在提交前就把「元」的十进制字符串
 * 转成整数分，并且不能留下浮点误差。
 */

/** 票价上限（分），与后端 priceCent 的 1—100000000 一致 */
export const MAX_PRICE_CENT = 100_000_000

/** 总库存上限，与后端 totalStock 的 1—1000000 一致 */
export const MAX_TOTAL_STOCK = 1_000_000

/** 最多两位小数的十进制正数，不接受负号、指数、千分位与空白 */
const DECIMAL_PATTERN = /^\d+(\.\d{1,2})?$/

/** 纯十进制整数，不接受符号与小数点 */
const INTEGER_PATTERN = /^\d+$/

export type ParseFailureReason = 'empty' | 'format' | 'range'

export type ParseResult =
  | { ok: true; value: number }
  | { ok: false; reason: ParseFailureReason }

/**
 * 把「元」的十进制字符串转成整数分。
 *
 * 全程按字符串拆分再拼成整数分的十进制表示，**不做 parseFloat(text) * 100**：
 * 后者在 99.99 这类值上会得到 9998.999999999998，截断后就少一分。
 */
export function yuanToCent(input: string): ParseResult {
  const text = input.trim()
  if (text === '') return { ok: false, reason: 'empty' }
  if (!DECIMAL_PATTERN.test(text)) return { ok: false, reason: 'format' }

  const [intPart, fracPart = ''] = text.split('.')
  // 补齐到两位后拼成整数分：'99' + '9' -> '9990'，'99' + '99' -> '9999'
  const centText = intPart + fracPart.padEnd(2, '0')
  const cent = Number(centText)

  if (!Number.isSafeInteger(cent) || cent < 1 || cent > MAX_PRICE_CENT) {
    return { ok: false, reason: 'range' }
  }
  return { ok: true, value: cent }
}

/**
 * 整数分转「元」的两位小数字符串，供表单回显。
 * 同样用整数取整与取余，不用浮点除法。
 */
export function centToYuan(cent: number | null | undefined): string {
  if (cent === null || cent === undefined) return ''
  if (!Number.isInteger(cent)) return ''

  const sign = cent < 0 ? '-' : ''
  const abs = Math.abs(cent)
  const yuan = Math.floor(abs / 100)
  const fen = abs % 100
  return `${sign}${yuan}.${String(fen).padStart(2, '0')}`
}

/** 把库存输入解析成整数；空串表示「未填写」 */
export function parseStock(input: string): ParseResult {
  const text = input.trim()
  if (text === '') return { ok: false, reason: 'empty' }
  if (!INTEGER_PATTERN.test(text)) return { ok: false, reason: 'format' }

  const stock = Number(text)
  if (!Number.isSafeInteger(stock) || stock < 1 || stock > MAX_TOTAL_STOCK) {
    return { ok: false, reason: 'range' }
  }
  return { ok: true, value: stock }
}

/** 把解析失败原因转成给用户看的中文提示 */
export function describeParseFailure(reason: ParseFailureReason, unit: string): string {
  switch (reason) {
    case 'empty':
      return `请填写${unit}`
    case 'format':
      return `${unit}格式不正确`
    case 'range':
      return `${unit}超出允许范围`
  }
}

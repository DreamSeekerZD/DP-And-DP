/** 演出状态；数据库内部是 0/1/2，对外只有这三个字符串 */
export type PerformanceStatus = 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'

/** 不可售原因；可售时为 null。判断顺序：未发布 → 已开始 → 售罄 */
export type UnavailableReason = 'NOT_PUBLISHED' | 'STARTED' | 'SOLD_OUT'

/**
 * 演出响应对象，公开接口与运营接口共用。
 *
 * id / publisherId 是 BIGINT，后端序列化成十进制**字符串**，前端不得转 Number。
 * 时间统一是 UTC 的 `...Z` 形式，展示时转北京时间。草稿的可空字段明确为 null。
 * canPurchase 只表达演出层面是否可售，**不判断当前用户是否已购买**。
 */
export interface Performance {
  id: string
  title: string | null
  description: string | null
  coverUrl: string | null
  venue: string | null
  startsAt: string | null
  priceCent: number | null
  totalStock: number | null
  availableStock: number | null
  status: PerformanceStatus
  publisherId: string
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  /** 本次响应时刻的数据库时间，用于校准「已开始」判断 */
  serverTime: string
  canPurchase: boolean
  unavailableReason: UnavailableReason | null
}

/**
 * 新建草稿与编辑演出的请求体。
 *
 * PUT 是**完整对象替换**而不是 PATCH：已发布/已下架的演出必须把关键字段原样带回，
 * 否则会被后端判定为「试图修改被锁定字段」并整笔 409。
 *
 * 不能出现 publisherId / status / publishedAt / availableStock —— 后端开启了
 * fail-on-unknown-properties，这类字段会让请求直接 400。
 * priceCent 与 totalStock 必须是 JSON 整数，浮点（含 99.0）一律 400。
 */
export interface SavePerformancePayload {
  title: string | null
  description: string | null
  coverUrl: string | null
  venue: string | null
  /** 必须带时区偏移，例如 2026-10-05T20:00:00+08:00；无偏移 400 */
  startsAt: string | null
  priceCent: number | null
  totalStock: number | null
}

/** 状态的中文展示名 */
export const PERFORMANCE_STATUS_LABEL: Record<PerformanceStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  WITHDRAWN: '已下架',
}

/** 不可售原因的中文展示名 */
export const UNAVAILABLE_REASON_LABEL: Record<UnavailableReason, string> = {
  NOT_PUBLISHED: '尚未发布',
  STARTED: '演出已开始',
  SOLD_OUT: '已售罄',
}

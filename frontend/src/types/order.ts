/** 订单状态；数据库内部是 0/1/2，对外只有这三个字符串 */
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'CLOSED'

/** 关闭原因；未关闭时为 null。只有用户取消与支付超时两种 */
export type OrderCloseReason = 'USER_CANCEL' | 'PAY_TIMEOUT'

/**
 * 订单页面输出对象，本人订单列表与详情共用。
 *
 * 演出标题、地点、开始时间与金额都是**下单时的成交快照**，演出后续改名或下架不影响这里。
 * id / performanceId 是十进制字符串，不得转 Number；时间统一 UTC 的 `...Z` 形式。
 * canPay / canCancel 是「当前响应时刻」的资格提示，不能替代写接口的校验。
 * 本阶段**没有支付接口**：即使 canPay=true 也不展示可调用的付款按钮。
 */
export interface Order {
  id: string
  performanceId: string
  performanceTitle: string | null
  performanceVenue: string | null
  performanceStartsAt: string | null
  amountCent: number | null
  status: OrderStatus
  closeReason: OrderCloseReason | null
  createdAt: string
  expireAt: string
  paidAt: string | null
  closedAt: string | null
  ticketNo: string | null
  /** 服务端当前时间，倒计时以此为准，不使用本地墙上时钟 */
  serverTime: string
  /** 待支付且 serverTime 早于 expireAt；只描述资格，不代表本阶段可付款 */
  canPay: boolean
  /** 待支付即可取消；已到期也允许，后端会记 PAY_TIMEOUT */
  canCancel: boolean
}

/** 下单结果：created=false（已有有效单）也是 200 成功，不是错误 */
export interface PlaceOrderResult {
  created: boolean
  order: Order
}

/** 状态的展示名 */
export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  CLOSED: '已关闭',
}

/** 关闭原因的展示名 */
export const CLOSE_REASON_LABEL: Record<OrderCloseReason, string> = {
  USER_CANCEL: '用户取消',
  PAY_TIMEOUT: '支付超时',
}
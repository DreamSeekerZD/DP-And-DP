import type { PageResult } from '@/types/api'
import type { OperatorOrder, Order, OrderStatus, PlaceOrderResult } from '@/types/order'
import { get, post } from '@/utils/request'

/** 我的订单列表查询参数；status 只接受 PENDING_PAYMENT/PAID/CLOSED */
export interface OrderQuery {
  page?: number
  pageSize?: number
  performanceId?: string
  status?: OrderStatus
}

/**
 * 下单，每单固定一张。
 * 返回 200：created=true 表示真的新建并占票；created=false 表示本人已有有效单，
 * 两种情况都是成功，**不能把 created=false 当成系统错误**。
 */
export function placeOrder(performanceId: string): Promise<PlaceOrderResult> {
  return post<PlaceOrderResult>('/orders', { performanceId })
}

/** 本人订单列表，可按演出与状态筛选；只看得到自己的订单 */
export function fetchMyOrders(query: OrderQuery = {}): Promise<PageResult<Order>> {
  return get<PageResult<Order>>('/orders', { ...query })
}

/** 本人订单详情：成交快照、状态、时间与当前可操作性；他人的 404 */
export function fetchOrder(id: string): Promise<Order> {
  return get<Order>(`/orders/${id}`)
}

/**
 * 取消待支付订单。不接受任何参数：目标状态、关闭原因与库存释放都由服务端决定。
 * 重复取消返回原结果，不会重复释放库存。
 */
export function cancelOrder(id: string): Promise<Order> {
  return post<Order>(`/orders/${id}/cancel`, {})
}

/**
 * 模拟付款。不接受任何参数：金额、用户、目标状态都由服务端决定，注入字段会被 400 拒绝。
 * 已支付重复付款返回原票号与原支付时间；CLOSED 409 ORDER_CLOSED；到期 409 PAYMENT_EXPIRED。
 * 票号只来自服务端，前端**不生成**票号。
 */
export function simulatePayment(id: string): Promise<Order> {
  return post<Order>(`/orders/${id}/simulate-payment`, {})
}

/** 运营查询本人演出的订单；query 只允许 page/pageSize/status */
export interface OperatorOrderQuery {
  page?: number
  pageSize?: number
  status?: OrderStatus
}

/**
 * 运营自己的演出订单分页。先校验演出归属（非本人/不存在 404），
 * 输出 OperatorOrderVO（没有票号、用户隐私，也不含 canPay/canCancel）。
 */
export function fetchOperatorOrders(
  performanceId: string,
  query: OperatorOrderQuery = {},
): Promise<PageResult<OperatorOrder>> {
  return get<PageResult<OperatorOrder>>(`/operator/performances/${performanceId}/orders`, {
    ...query,
  })
}
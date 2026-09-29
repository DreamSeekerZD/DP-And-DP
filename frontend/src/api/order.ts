import type { PageResult } from '@/types/api'
import type { Order, OrderStatus, PlaceOrderResult } from '@/types/order'
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
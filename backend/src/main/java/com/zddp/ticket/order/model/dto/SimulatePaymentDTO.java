package com.zddp.ticket.order.model.dto;

/**
 * 模拟付款的请求体，对应 POST /api/orders/{id}/simulate-payment。
 *
 * <p>付款不需要任何业务参数：金额取自订单成交快照、用户身份取自服务端会话、
 * 目标状态由服务端根据订单当前状态与数据库时间决定。
 * 因此这是<b>刻意的空类型</b>：让「无正文」与「{}」都能通过，
 * 而任何字段（例如试图指定金额、票号或目标状态）都会被未知字段规则挡下并返回 400。
 *
 * <p>不用通用 Map 承接请求体，避免万能请求上下文。
 */
public class SimulatePaymentDTO {
}
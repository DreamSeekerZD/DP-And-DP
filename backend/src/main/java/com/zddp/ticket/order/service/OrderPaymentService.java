package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单内的<b>模拟付款</b>：一次决定终态、一次出票，不涉及真实资金或第三方网关。
 *
 * <p>按 payment-v1 契约的顺序执行：
 * <ol>
 *   <li>锁定<b>本人</b>订单（SELECT ... FOR UPDATE）——在其他人与不存在之间统一 404，
 *       避免通过响应差异探测别人的订单一；</li>
 *   <li>已支付：直接返回原票号与原支付时间，<b>不再生成或覆盖票号</b>；
 *       已关闭：409 ORDER_CLOSED，不改任何字段；</li>
 *   <li>拿到锁之后<b>单独重新读取</b>数据库当前时间——等待锁的期间时间可能已越过截止，
 *       必须以这一份新鲜时间为准，不能用请求发起时刻；</li>
 *   <li>now 不早于 expireAt（含恰好相等）：409 PAYMENT_EXPIRED，且<b>不附带关闭动作</b>，
 *       订单保持在待支付，由既有的取消 / 超时扫描负责关闭；</li>
 *   <li>否则条件更新 status = 0 -> 1 并一次写入 paidAt / ticketNo，影响行数必须是 1，
 *       否则属于数据异常，整笔报内部错误并回滚。</li>
 * </ol>
 *
 * <p>付款<b>不</b>：检查演出是否仍上架（下架的有效待支付单仍可付款）、不扣减库存、
 * 不触碰 active_marker。这些都在更早的下单 / 关单链路处理过。
 *
 * <p>锁竞争（死锁 / 锁等待超时）由事务代理退出后，全局异常处理器统一映射为
 * 503 SERVICE_BUSY；本方法不捕获也不吞掉事务异常。连接中断等结果未知的错误
 * 同样不在这里自动重试——客户端应查询原订单确认，而不是重新提交付款。
 */
@Service
public class OrderPaymentService {

    private final OrderMapper orderMapper;

    private final OrderQueryService orderQueryService;

    public OrderPaymentService(OrderMapper orderMapper, OrderQueryService orderQueryService) {
        this.orderMapper = orderMapper;
        this.orderQueryService = orderQueryService;
    }

    /**
     * 对本人订单执行模拟付款。
     *
     * <p>任何分支都复用 {@link OrderQueryService#toVO} 组装响应，
     * 保证 canPay/canCancel 与外层订单查询对同一线程只给出一致答案。
     */
    @Transactional
    public OrderVO pay(Long userId, Long orderId) {
        requirePositiveId(orderId);

        TicketOrder order = orderMapper.selectOwnedForUpdate(orderId, userId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在或不属于当前用户");
        }

        if (order.getStatus() == OrderStatus.PAID) {
            // 重复支付：返回原成功结果与原票号，不再次出票
            return orderQueryService.toVO(order, orderMapper.selectDatabaseNowUtc());
        }
        if (order.getStatus() == OrderStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_CLOSED", "订单已关闭，不能支付");
        }

        // 走到这里一定是待支付。拿锁之后重新取数据库时间，用它判断是否已过截止
        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        if (!now.isBefore(order.getExpireAt())) {
            // 时间相等即视为已到期。拒付但不偷偷关单，交给既有取消/扫描链路
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_EXPIRED",
                    "订单已超过支付截止时间，无法支付");
        }

        String ticketNo = generateTicketNo();
        int affected = orderMapper.payPending(orderId, now, ticketNo);
        if (affected != 1) {
            // 已持有本行排它锁且读到的是待支付，这条条件更新理应命中 1 行；
            // 命中 0 行说明有未被预期的写入，属于数据异常，整笔回滚，不能假装支付成功
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "订单状态异常：无法写入支付结果，已回滚");
        }

        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(now);
        order.setTicketNo(ticketNo);
        return orderQueryService.toVO(order, now);
    }

    /**
     * 生成票号：{@code T} + 去横线的随机 UUID（共 33 字符）。
     *
     * <p>ticket_no 的唯一索引是最终兜底：万一随机碰撞发生于 DB 写入，
     * 那次 UPDATE 会抛唯一约束异常，事务整笔回滚，不吞异常返回成功，也不无限重试。
     * 票号不是订单号，也不作为任何访问授权的令牌。
     */
    private String generateTicketNo() {
        return "T" + UUID.randomUUID().toString().replace("-", "");
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.validation("订单编号必须是正整数");
        }
    }
}
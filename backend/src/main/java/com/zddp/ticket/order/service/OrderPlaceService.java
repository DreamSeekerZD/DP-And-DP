package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.order.model.vo.PlaceOrderVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 下单入口：把「查已有单 → 建单 → 撞唯一约束后再查」编排起来。
 *
 * <p><b>本类刻意没有 {@code @Transactional}。</b> 这是整个下单链路最关键的约定：
 * 建单必须在一个能完整回滚的独立事务里失败，只有事务退出之后，
 * 调用方才可能重新查询原单。如果在外层再包一个事务：
 * <ul>
 *   <li>唯一约束冲突会把外层事务标记为 rollback-only，之后任何查询都不再可信；</li>
 *   <li>死锁/锁等待失败同样会污染外层事务，无法再做「查询确认」。</li>
 * </ul>
 *
 * <p>三类失败的处理方式：
 * <ul>
 *   <li><b>唯一约束冲突</b>：只在撞到 uk_order_user_performance_active 时才算
 *       「同一用户对同一演出已有有效单」，退出事务后重新查询并复用原单；
 *       其他唯一约束（例如票号）说明是别的问题，原样抛出，不能吞成「重复下单成功」。</li>
 *   <li><b>原单在这一瞬间被关闭</b>：属于允许的竞态，最多再整体尝试一次，总共不超过两次建单；
 *       两次都冲突则返回 503，让客户端稍后查询而不是继续重试。</li>
 *   <li><b>死锁 / 锁等待超时</b>：整笔已回滚，返回 503 SERVICE_BUSY，
 *       并且不承诺「肯定没下单」——客户端应先查询我的订单，不要自动重放写请求。</li>
 * </ul>
 *
 * <p>连接中断之类结果未知的错误不在这里处理，也不做自动重试：那类错误无法区分
 * 「没下单」和「下了但响应丢了」，盲目重试可能产生第二笔订单。
 */
@Service
public class OrderPlaceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlaceService.class);

    /** 只有撞到这个唯一约束才算「同一用户对同一演出已有有效单」 */
    private static final String ACTIVE_ORDER_UNIQUE_KEY = "uk_order_user_performance_active";

    /** 建单总尝试次数：第一次 + 应对「原单刚被关闭」竞态的一次重试 */
    private static final int MAX_CREATE_ATTEMPTS = 2;

    private final OrderMapper orderMapper;

    private final OrderCreateService orderCreateService;

    private final OrderCloseService orderCloseService;

    private final OrderQueryService orderQueryService;

    public OrderPlaceService(OrderMapper orderMapper,
                             OrderCreateService orderCreateService,
                             OrderCloseService orderCloseService,
                             OrderQueryService orderQueryService) {
        this.orderMapper = orderMapper;
        this.orderCreateService = orderCreateService;
        this.orderCloseService = orderCloseService;
        this.orderQueryService = orderQueryService;
    }

    /**
     * 下单。
     *
     * <p>先看有没有可复用的有效订单，这一步<b>优先于</b>演出是否售罄、是否下架的检查：
     * 已经买到票的用户，即使演出后来下架了，也应该能拿回属于自己的那张订单。
     */
    public PlaceOrderVO place(Long userId, Long performanceId) {
        requirePositiveId(performanceId);

        TicketOrder active = orderMapper.selectActiveByUserAndPerformance(userId, performanceId);
        PlaceOrderVO reused = tryReuse(active);
        if (reused != null) {
            return reused;
        }
        if (active != null) {
            // 原单是待支付且已到期：先用独立事务关闭并释放一张票，再重新尝试下单。
            // 关闭与重买分属两个事务，释放出来的票也可能被别人先买走，这是允许的。
            orderCloseService.closeExpired(active.getId());
        }

        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            try {
                OrderVO created = orderCreateService.create(userId, performanceId);
                return new PlaceOrderVO(true, created);
            } catch (DuplicateKeyException ex) {
                if (!isActiveOrderConflict(ex)) {
                    // 别的唯一约束冲突，不是「已有有效单」，原样抛出交全局处理器
                    throw ex;
                }
                // 到这里 create 的事务已经完整回滚，可以安全地重新查询
                PlaceOrderVO afterConflict =
                        tryReuse(orderMapper.selectActiveByUserAndPerformance(userId, performanceId));
                if (afterConflict != null) {
                    return afterConflict;
                }
                // 冲突的那条原单在两次查询之间被关闭了：再整体尝试一次
                log.info("下单撞唯一约束后未查到有效单，重试一次 userId={} performanceId={}",
                        userId, performanceId);
            } catch (ConcurrencyFailureException ex) {
                // 死锁或锁等待超时：事务已整体回滚，不对结果作任何承诺
                log.warn("下单遇到锁冲突 userId={} performanceId={} cause={}",
                        userId, performanceId, ex.getMessage());
                throw serviceBusy();
            }
        }

        // 两次尝试都撞到唯一约束说明并发压力持续存在，交给客户端稍后查询
        throw serviceBusy();
    }

    /**
     * 尝试复用一条已有订单。
     *
     * <p>已支付：无论是否到期都原样返回（本阶段不退款，已支付就是最终结果）。
     * 待支付未到期：原样返回。
     * 待支付已到期：返回 null，表示需要先关闭再重新下单。
     */
    private PlaceOrderVO tryReuse(TicketOrder order) {
        if (order == null) {
            return null;
        }
        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        boolean paid = order.getStatus() == OrderStatus.PAID;
        if (paid || now.isBefore(order.getExpireAt())) {
            return new PlaceOrderVO(false, orderQueryService.toVO(order, now));
        }
        return null;
    }

    /**
     * 判断这个唯一冲突是不是「同一用户对同一演出已有有效单」。
     * 按约束名识别，避免把所有唯一冲突都当成重复下单而掩盖真实问题。
     */
    private boolean isActiveOrderConflict(DuplicateKeyException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause == null ? ex.getMessage() : cause.getMessage();
        return message != null && message.contains(ACTIVE_ORDER_UNIQUE_KEY);
    }

    private BusinessException serviceBusy() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                "下单请求发生并发冲突，请稍后查询我的订单确认结果，不要直接重复提交");
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.validation("演出编号必须是正整数");
        }
    }
}

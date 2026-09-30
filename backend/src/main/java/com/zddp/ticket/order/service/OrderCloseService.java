package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.order.enums.OrderCloseReason;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.performance.service.PerformanceInventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 关闭订单：用户主动取消与后台到期关闭，两个入口、一个动作。
 *
 * <p>两个公开方法各自是<b>独立事务</b>：
 * <ul>
 *   <li>{@link #cancel(Long, Long)} 走用户入口，按订单号 + 用户编号加锁，防止越权；</li>
 *   <li>{@link #closeExpired(Long)} 走后台入口，只按订单号加锁——它的调用方是定时扫描，
 *       依据是扫描出来的订单号，不接受浏览器身份。</li>
 * </ul>
 * 共用同一个私有关闭动作，保证「改状态」和「还库存」永远在同一笔事务里，
 * 不会出现订单关了票没还、或者票还了订单还开着的中间状态。
 *
 * <p>关闭原因由<b>数据库当前时间</b>决定，不是请求发起时间：
 * 未到期是 USER_CANCEL，到期或时间相等是 PAY_TIMEOUT。
 */
@Service
public class OrderCloseService {

    private final OrderMapper orderMapper;

    private final PerformanceInventoryService inventoryService;

    private final OrderQueryService orderQueryService;

    public OrderCloseService(OrderMapper orderMapper,
                             PerformanceInventoryService inventoryService,
                             OrderQueryService orderQueryService) {
        this.orderMapper = orderMapper;
        this.inventoryService = inventoryService;
        this.orderQueryService = orderQueryService;
    }

    /**
     * 用户取消自己的订单。
     *
     * <p>拿锁之后按数据库时间判断原因；已关闭的订单重复取消返回原结果且不再释放库存；
     * 已支付的订单拒绝取消（本阶段不支持退款）。
     */
    @Transactional
    public OrderVO cancel(Long userId, Long orderId) {
        requirePositiveId(orderId);

        TicketOrder order = orderMapper.selectOwnedForUpdate(orderId, userId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在或不属于当前用户");
        }

        if (order.getStatus() == OrderStatus.CLOSED) {
            // 重复取消：返回原结果，绝不再释放一次库存
            return orderQueryService.toVO(order, orderMapper.selectDatabaseNowUtc());
        }
        if (order.getStatus() == OrderStatus.PAID) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_PAID", "订单已支付，不支持取消");
        }

        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        // 时间相等即视为已到期
        OrderCloseReason reason = now.isBefore(order.getExpireAt())
                ? OrderCloseReason.USER_CANCEL
                : OrderCloseReason.PAY_TIMEOUT;
        closeOrder(order, reason, now);
        return orderQueryService.toVO(order, now);
    }

    /**
     * 后台到期关闭，由定时扫描逐单调用的独立事务。
     *
     * <p>已支付、已关闭、以及还没到期的订单都<b>正常跳过</b>，不抛异常、不释放库存——
     * 扫描候选是在别的事务里选出来的，真正处理时状态可能已经变了，跳过是预期行为而不是错误。
     */
    @Transactional
    public void closeExpired(Long orderId) {
        requirePositiveId(orderId);

        TicketOrder order = orderMapper.selectForUpdateById(orderId);
        if (order == null) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return;
        }
        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        if (now.isBefore(order.getExpireAt())) {
            // 候选是之前选出来的，这中间可能已经被改过截止时间；没到期就不关
            return;
        }
        closeOrder(order, OrderCloseReason.PAY_TIMEOUT, now);
    }

    /**
     * 共用的关闭动作：条件更新为已关闭 + 恢复一张库存，两者同一事务。
     *
     * <p>顺序是先改订单再还库存；任何一步不成功都抛异常让整笔回滚，
     * 因此不会出现「状态改了但库存没恢复」或反之。
     */
    private void closeOrder(TicketOrder order, OrderCloseReason reason, LocalDateTime now) {
        int affected = orderMapper.closePending(order.getId(), reason, now);
        if (affected != 1) {
            // 已经持有该行的排它锁，且刚才读到的是「待支付」，
            // 因此这条条件更新理应命中 1 行。命中 0 行说明存在未被预期的写入，
            // 属于数据/逻辑异常而不是并发竞争，直接整笔回滚并报内部错误。
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "订单状态异常：条件更新未命中，已回滚本次关闭");
        }

        if (!inventoryService.releaseOne(order.getPerformanceId())) {
            // 可售库存已经等于总库存：说明库存被多释放过或数据被外部改过。
            // 必须整笔回滚，绝不能留下「订单已关闭但票没还」。
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "库存恢复失败：可售库存已达总库存，已回滚本次关闭");
        }

        // 让内存中的对象与数据库一致，供调用方组装响应
        order.setStatus(OrderStatus.CLOSED);
        order.setCloseReason(reason);
        order.setClosedAt(now);
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.validation("订单编号必须是正整数");
        }
    }
}

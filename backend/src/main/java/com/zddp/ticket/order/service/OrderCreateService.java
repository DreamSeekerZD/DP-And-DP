package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.performance.model.dto.PerformancePurchaseDTO;
import com.zddp.ticket.performance.enums.PerformanceStatus;
import com.zddp.ticket.performance.service.PerformanceInventoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 建单：在**一个独立事务**里完成「插订单 + 扣库存」。
 *
 * <p>为什么必须是独立 Bean 的公开事务方法：{@link OrderPlaceService} 需要在**事务外**
 * 观察这次建单是成功、还是撞了唯一约束、还是死锁——这些异常必须先让事务完整回滚，
 * 调用方才可能去重新查询原单。如果建单和重试挤在同一个事务里，
 * 回滚标记会让后续任何查询都失去意义。
 *
 * <p>执行顺序与理由：
 * <ol>
 *   <li>读购买快照与数据库当前时间（不加锁，只用于判断与算截止）；</li>
 *   <li>先校验能否售卖，给出明确的 409 而不是等到扣库存失败才猜原因；</li>
 *   <li>插入订单。同一用户对同一演出已有有效单时，这里会撞唯一索引并抛异常，
 *       由调用方在事务外识别；</li>
 *   <li>条件扣减库存。这是真正的并发保护：条件在数据库拿到行锁之后重新求值，
 *       所以并发抢最后一张票只有一个事务能成功，另一个影响 0 行并整笔回滚；</li>
 *   <li>扣减成功后再取一次<b>新鲜</b>的数据库时间复核：等锁期间可能已经过了截止时间
 *       或已经开演，那这笔订单必须整体作废。</li>
 * </ol>
 * 任何一步失败都抛异常让整笔回滚——绝不会留下「占了票却没有订单」或
 * 「有订单却没扣票」的中间状态。
 */
@Service
public class OrderCreateService {

    private final OrderMapper orderMapper;

    private final PerformanceInventoryService inventoryService;

    private final OrderQueryService orderQueryService;

    /** 订单支付有效期；构造时校验为正数，配置写错会启动即失败而不是运行期行为诡异 */
    private final Duration orderTimeout;

    public OrderCreateService(OrderMapper orderMapper,
                              PerformanceInventoryService inventoryService,
                              OrderQueryService orderQueryService,
                              @Value("${ticket.order.timeout}") Duration orderTimeout) {
        if (orderTimeout == null || orderTimeout.isZero() || orderTimeout.isNegative()) {
            throw new IllegalStateException("配置 ticket.order.timeout 必须是正数");
        }
        this.orderMapper = orderMapper;
        this.inventoryService = inventoryService;
        this.orderQueryService = orderQueryService;
        this.orderTimeout = orderTimeout;
    }

    /**
     * 新建订单并占用一张票。
     *
     * <p>本方法不处理「已有有效订单」的情况——那是 {@link OrderPlaceService} 的职责，
     * 它必须先看到唯一冲突并把异常带出事务，才能去查原单。
     */
    @Transactional
    public OrderVO create(Long userId, Long performanceId) {
        PerformancePurchaseDTO purchase = inventoryService.getPurchaseInfo(performanceId);
        if (purchase == null) {
            throw BusinessException.notFound("演出不存在");
        }

        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        assertSaleable(purchase, now);

        // 截止时间取「下单时间 + 有效期」与「演出开始时间」中较早的一个：
        // 演出开始之后不允许再付款，所以不能把截止时间放到开演之后。
        LocalDateTime expireAt = now.plus(orderTimeout);
        if (expireAt.isAfter(purchase.getStartsAt())) {
            expireAt = purchase.getStartsAt();
        }

        TicketOrder order = new TicketOrder();
        order.setUserId(userId);
        order.setPerformanceId(purchase.getId());
        // 成交快照全部取自演出，金额不接受浏览器指定
        order.setPerformanceTitle(purchase.getTitle());
        order.setPerformanceVenue(purchase.getVenue());
        order.setPerformanceStartsAt(purchase.getStartsAt());
        order.setAmountCent(purchase.getPriceCent());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setCloseReason(null);
        order.setCreatedAt(now);
        order.setExpireAt(expireAt);
        order.setPaidAt(null);
        order.setClosedAt(null);
        order.setTicketNo(null);
        orderMapper.insertOrder(order);

        if (!inventoryService.reserveOne(performanceId)) {
            // 影响 0 行说明这条 UPDATE 的条件不成立。重新读一次演出，
            // 区分「售罄」「已下架」「已开始」，给出准确的业务码后整笔回滚。
            throw classifyReserveFailure(performanceId);
        }

        // 拿到库存行锁之后再取一次新鲜时间：等锁期间时间可能已经推进
        LocalDateTime freshNow = orderMapper.selectDatabaseNowUtc();
        if (!freshNow.isBefore(expireAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_SALEABLE",
                    "已到支付截止时间，请重新下单");
        }
        if (!freshNow.isBefore(purchase.getStartsAt())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_SALEABLE",
                    "演出已经开始，无法下单");
        }

        return orderQueryService.toVO(order, freshNow);
    }

    /** 首次校验：不存在已在上面处理，这里判断可售性 */
    private void assertSaleable(PerformancePurchaseDTO purchase, LocalDateTime now) {
        if (purchase.getStatus() != PerformanceStatus.PUBLISHED) {
            throw new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_SALEABLE", "演出未发布或已下架");
        }
        if (purchase.getStartsAt() == null || !purchase.getStartsAt().isAfter(now)) {
            throw new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_SALEABLE", "演出已经开始");
        }
        if (purchase.getAvailableStock() == null || purchase.getAvailableStock() <= 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "STOCK_INSUFFICIENT", "演出票已售罄");
        }
    }

    /**
     * 扣库存影响 0 行时，重新读一次演出判断真实原因。
     * 这里仍在原事务里，读完就抛异常让整笔回滚，不会留下已插入的订单。
     */
    private BusinessException classifyReserveFailure(Long performanceId) {
        PerformancePurchaseDTO latest = inventoryService.getPurchaseInfo(performanceId);
        if (latest == null) {
            return BusinessException.notFound("演出不存在");
        }
        if (latest.getStatus() != PerformanceStatus.PUBLISHED) {
            return new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_SALEABLE", "演出未发布或已下架");
        }
        LocalDateTime now = orderMapper.selectDatabaseNowUtc();
        if (latest.getStartsAt() == null || !latest.getStartsAt().isAfter(now)) {
            return new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_SALEABLE", "演出已经开始");
        }
        // 演出仍可售、也还没开始，那就只能是票被抢完了
        return new BusinessException(HttpStatus.CONFLICT, "STOCK_INSUFFICIENT", "演出票已售罄");
    }
}

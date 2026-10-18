package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.order.enums.OrderCloseReason;
import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.order.redis.OrderReservationException;
import com.zddp.ticket.order.redis.OrderReservationStore;
import com.zddp.ticket.performance.service.PerformanceInventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * 关闭订单：用户主动取消与后台到期关闭，两个入口、一个动作。
 *
 * <p>两个公开方法各自是<b>独立事务</b>：
 * <ul>
 *   <li>{@link #cancel(Long, Long)} 走用户入口，按订单号 + 用户编号加锁，防止越权；</li>
 *   <li>{@link #closeExpired(Long)} 走后台入口，只按订单号加锁——它的调用方是定时扫描或
 *       下单链路里「原单已到期」的分支，不接受浏览器身份。</li>
 * </ul>
 * 共用同一个私有关闭动作，保证「改状态」和「还 MySQL 库存」永远在同一笔事务里。
 *
 * <p><b>Redis 占用释放发生在数据库提交之后（B05 释放边界）</b>：数据库真实从待支付变为
 * 已关闭的那一笔提交成功后，才用 {@link TransactionSynchronization#afterCommit()} 同步调用
 * {@link OrderReservationStore#release} 一次。因此：
 * <ul>
 *   <li>重复取消、重复扫描已关闭订单不会进入释放分支（只在状态真实转换完成时注册）；</li>
 *   <li>事务回滚不会触发释放（afterCommit 只在 commits 时回调）；</li>
 *   <li>数据库已提交但 Redis 释放失败时，<b>不报告数据库回滚</b>——关闭结果以 MySQL 为准，
 *       记录维护信息并暂停该场（连接故障则全局暂停）新下单，禁止业务层自动重试 Redis 写。</li>
 * </ul>
 */
@Service
public class OrderCloseService {

    private static final Logger log = LoggerFactory.getLogger(OrderCloseService.class);

    private final OrderMapper orderMapper;

    private final PerformanceInventoryService inventoryService;

    private final OrderQueryService orderQueryService;

    /** B05：Redis 预占组件；关闭提交后释放占用，只操作两 key，不查 MySQL */
    private final OrderReservationStore reservationStore;

    /** B05：Redis 数据异常/连接故障时暂停新下单准入（GET/支付不受影响） */
    private final OrderAdmissionGate admissionGate;

    public OrderCloseService(OrderMapper orderMapper,
                             PerformanceInventoryService inventoryService,
                             OrderQueryService orderQueryService,
                             OrderReservationStore reservationStore,
                             OrderAdmissionGate admissionGate) {
        this.orderMapper = orderMapper;
        this.inventoryService = inventoryService;
        this.orderQueryService = orderQueryService;
        this.reservationStore = reservationStore;
        this.admissionGate = admissionGate;
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
     * 后台到期关闭或下单链路「原单已到期」关闭，由调用方在独立事务里调用。
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
     * 共用的关闭动作：条件更新为已关闭 + 恢复一张 MySQL 库存，两者同一事务；
     * 状态真实转换成功后，注册「提交后释放 Redis 占用」（一次）。
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

        // 数据库真实从待支付变为已关闭且提交成功后，才释放 Redis 占用一次
        registerRedisReleaseAfterCommit(order);
    }

    /**
     * 注册事务提交后的 Redis 释放回调。
     * 只有走到这里（closePending 命中 1 行、MySQL 库存已还）才算“真实关闭”；
     * afterCommit 只在提交成功时回调，事务回滚不会调用。
     */
    private void registerRedisReleaseAfterCommit(TicketOrder order) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 防御：生产环境关闭订单总是在 @Transactional 方法内执行，同步必然激活。
            // 测试 profile 没有 DataSource/事务管理器时 @Transactional 不生效，这里只告警、
            // 不暂停任何准入，避免测试上下文互相污染；Redis 释放留给人工作业核对。
            log.warn("关闭订单时事务同步未激活，跳过提交后 Redis 释放（仅无事务管理器环境会出现）："
                    + "orderId={} performanceId={}", order.getId(), order.getPerformanceId());
            return;
        }
        final Long performanceId = order.getPerformanceId();
        final Long userId = order.getUserId();
        final Long orderId = order.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseRedisAfterCommit(performanceId, userId, orderId);
            }
        });
    }

    /**
     * 提交后的 Redis release。任何失败都不能把已提交的关闭说成回滚：
     * 关闭结果以 MySQL 为准，记录维护信息并暂停准入，禁止自动重试 Redis 写。
     */
    private void releaseRedisAfterCommit(Long performanceId, Long userId, Long orderId) {
        try {
            reservationStore.release(performanceId, userId);
        } catch (RedisConnectionFailureException ex) {
            log.error("订单已提交关闭（orderId={}, perfId={}），Redis 连接失败，需人工维护后重启开放准入",
                    orderId, performanceId, ex);
            admissionGate.pauseGlobalAdmission();
        } catch (OrderReservationException ex) {
            log.error("订单已提交关闭（orderId={}, perfId={}），Redis 占用数据异常，需人工维护后重启开放准入",
                    orderId, performanceId, ex);
            admissionGate.pausePerformance(performanceId);
        } catch (RuntimeException ex) {
            log.error("订单已提交关闭（orderId={}, perfId={}），Redis 释放返回未预期异常，需人工维护",
                    orderId, performanceId, ex);
            admissionGate.pauseGlobalAdmission();
        }
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.validation("订单编号必须是正整数");
        }
    }
}
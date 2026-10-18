package com.zddp.ticket.order.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.order.diagnostic.OrderTimingProbe;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.model.vo.OrderVO;
import com.zddp.ticket.order.model.vo.PlaceOrderVO;
import com.zddp.ticket.order.redis.OrderReservationException;
import com.zddp.ticket.order.redis.OrderReservationResult;
import com.zddp.ticket.order.redis.OrderReservationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;

import java.time.LocalDateTime;

/**
 * 下单入口（B05 预占编排版）：先 Redis 预占过滤，再进数据库建单。
 *
 * <p><b>本类刻意没有 {@code @Transactional}。</b> 建单在独立事务里失败后，只有事务退出，
 * 调用方才可能安全地释放预占或重查原单；外层再包事务会让回滚标记污染后续查询。
 *
 * <p>主流程（B05 README）：
 * <ol>
 *   <li><b>预占</b>（{@link OrderReservationStore#reserve}，只操作 Redis，不查 MySQL）：</li>
 *   <li><b>RESERVED</b> → 调 {@link OrderCreateService#create} 建单。正常路径<b>不再前置查询
 *       已有订单</b>；建单明确回滚时释放预占一次，提交结果未知时不释放、不重做；</li>
 *   <li><b>OCCUPIED</b> → 已有占用，此时才查询本人有效订单：可复用返回原单（created=false）；
 *       原单已到期则先独立事务关闭（提交后释放 Redis），再最多重新预占一次；查不到订单返回
 *       ORDER_PROCESSING，绝不新建第二单；</li>
 *   <li><b>SOLD_OUT</b> → 409 STOCK_INSUFFICIENT，不查订单、不建单；</li>
 *   <li><b>NOT_READY</b> → 503 ORDER_ADMISSION_UNAVAILABLE 并暂停该场新下单。</li>
 * </ol>
 *
 * <p><b>释放/不释放边界（B04/B09）</b>：
 * <ul>
 *   <li>建单明确回滚（业务失败、死锁、rollback-only 回滚、非「已有有效单」的唯一冲突）→ 释放预占一次；</li>
 *   <li>建单撞「已有有效单」唯一约束（缓存缺标记的不一致）→ <b>不释放</b>当前占用户标记，
 *       暂停该场准入并报告，退出事务后查原单返回，不自动重试建单；</li>
 *   <li>提交结果未知（连接在提交时刻中断等）→ <b>不释放、不重做</b>，返回 503 ORDER_RESULT_UNKNOWN，
 *       客户端先查询订单，由人工维护核对。</li>
 * </ul>
 */
@Service
public class OrderPlaceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlaceService.class);

    /** 只有撞到这个唯一约束才算「同一用户对同一演出已有有效单」 */
    private static final String ACTIVE_ORDER_UNIQUE_KEY = "uk_order_user_performance_active";

    private final OrderMapper orderMapper;

    private final OrderCreateService orderCreateService;

    private final OrderCloseService orderCloseService;

    private final OrderQueryService orderQueryService;

    /** B05：Redis 预占组件 */
    private final OrderReservationStore orderReservationStore;

    /** B05：单实例内存准入闸（Redis 异常时暂停新下单，GET/支付/取消不受影响） */
    private final OrderAdmissionGate admissionGate;

    /** 下单耗时诊断探针；默认关闭，只对指定演出计时，不影响任何业务行为 */
    private final OrderTimingProbe timingProbe;

    public OrderPlaceService(OrderMapper orderMapper,
                             OrderCreateService orderCreateService,
                             OrderCloseService orderCloseService,
                             OrderQueryService orderQueryService,
                             OrderReservationStore orderReservationStore,
                             OrderAdmissionGate admissionGate,
                             OrderTimingProbe timingProbe) {
        this.orderMapper = orderMapper;
        this.orderCreateService = orderCreateService;
        this.orderCloseService = orderCloseService;
        this.orderQueryService = orderQueryService;
        this.orderReservationStore = orderReservationStore;
        this.admissionGate = admissionGate;
        this.timingProbe = timingProbe;
    }

    /**
     * 下单（每单固定一张）。
     */
    public PlaceOrderVO place(Long userId, Long performanceId) {
        requirePositiveId(performanceId);
        timingProbe.beginPlace(performanceId);
        PlaceOrderVO result = null;
        try {
            // 准入检查不再是只在入口做一次：每次 reserve（含重预占）前都检查。
            OrderReservationResult reservation = reserveWithAdmissionCheck(userId, performanceId);
            result = proceedAfterReserve(userId, performanceId, reservation);
            return result;
        } finally {
            // 诊断计时：只在 created=true 时记成功；复用/异常累计为排除请求
            timingProbe.finishPlace(result != null && result.isCreated());
        }
    }

    // ------------------------------------------------------------ 预占与分支

    /**
     * 每次预占前的统一入口：(1) 检查当前场/全局准入，暂停时直接 503，一次 Redis 都不调用；
     * (2) 再执行预占并把 Redis 不可用/数据异常映射为 503 + 暂停。
     * 过期重购的「再次预占」也必须走这里，否则关闭提交后释放失败导致的暂停会被本次请求绕开。
     */
    private OrderReservationResult reserveWithAdmissionCheck(Long userId, Long performanceId) {
        if (!admissionGate.isAdmissionOpen(performanceId)) {
            throw admissionUnavailable("订单准入已暂停，请先查询我的订单");
        }
        return reserveWithFailureMapping(performanceId, userId);
    }

    /**
     * 预占并把 Redis 不可用/数据异常映射为 503 + 暂停。
     * Redis 连接故障：全局暂停；预占数据损坏：单场暂停。
     */
    private OrderReservationResult reserveWithFailureMapping(Long performanceId, Long userId) {
        try {
            return orderReservationStore.reserve(performanceId, userId, System.currentTimeMillis());
        } catch (RedisConnectionFailureException ex) {
            log.error("Redis 不可用，暂停全局新下单准入 performanceId={} userId={} cause={}",
                    performanceId, userId, ex.getMessage());
            admissionGate.pauseGlobalAdmission();
            throw admissionUnavailable("购票服务暂不可用，请先查询已有订单");
        } catch (DataAccessException ex) {
            log.error("Redis 预占执行异常，暂停全局新下单准入 performanceId={} userId={} cause={}",
                    performanceId, userId, ex.getMessage());
            admissionGate.pauseGlobalAdmission();
            throw admissionUnavailable("购票服务暂不可用，请先查询已有订单");
        } catch (OrderReservationException ex) {
            log.error("Redis 预占数据异常，暂停该场新下单准入 performanceId={} userId={} cause={}",
                    performanceId, userId, ex.getMessage());
            admissionGate.pausePerformance(performanceId);
            throw admissionUnavailable("本场演出数据异常，请先查询已有订单");
        }
    }

    private PlaceOrderVO proceedAfterReserve(Long userId, Long performanceId, OrderReservationResult reservation) {
        switch (reservation) {
            case RESERVED:
                return createAfterReserve(userId, performanceId);
            case OCCUPIED:
                return occupiedAfterReserve(userId, performanceId);
            case SOLD_OUT:
                throw new BusinessException(HttpStatus.CONFLICT, "STOCK_INSUFFICIENT", "演出票已售罄");
            case NOT_READY:
                admissionGate.pausePerformance(performanceId);
                throw admissionUnavailable("本场演出数据未就绪，请先查询已有订单");
            default:
                // Lua 返回了未知结果，属数据异常，暂停该场并保守拒绝
                admissionGate.pausePerformance(performanceId);
                throw admissionUnavailable("预占结果未知，请先查询已有订单");
        }
    }

    // ------------------------------------------------------------ RESERVED：建单

    private PlaceOrderVO createAfterReserve(Long userId, Long performanceId) {
        boolean success = false;
        try {
            timingProbe.begin(performanceId);
            try {
                OrderVO created = orderCreateService.create(userId, performanceId);
                success = true;
                return new PlaceOrderVO(true, created);
            } finally {
                timingProbe.finish(success);
            }
        } catch (DuplicateKeyException ex) {
            if (!isActiveOrderConflict(ex)) {
                // 别的唯一约束冲突（例如票号碰撞）：创建已回滚，释放本次预占后原样抛出
                releaseAfterRollback(performanceId, userId);
                throw ex;
            }
            // B09：Redis 预占成功但数据库唯一约束命中「已有有效单」——缓存缺标记，
            // 属数据不一致。保留当前占用户标记（不释放）、暂停该场准入并报告维护，
            // 不自动重试建单；退出事务后查原单返回。
            admissionGate.pausePerformance(performanceId);
            log.error("缓存与数据库不一致：Redis 预占成功但撞 uk_order_user_performance_active，"
                    + "暂停该场准入交人工恢复 performanceId={} userId={}", performanceId, userId);
            TicketOrder active = orderMapper.selectActiveByUserAndPerformance(userId, performanceId);
            PlaceOrderVO reused = tryReuse(active);
            if (reused != null) {
                return reused;
            }
            throw admissionUnavailable("数据不一致，请稍后查询我的订单，系统恢复前不要重复提交");
        } catch (ConcurrencyFailureException ex) {
            // 死锁/锁等待：事务已明确回滚，释放本次预占一次
            log.warn("下单遇到锁冲突 userId={} performanceId={} cause={}",
                    userId, performanceId, ex.getMessage());
            releaseAfterRollback(performanceId, userId);
            throw serviceBusy();
        } catch (UnexpectedRollbackException ex) {
            // 事务被标记 rollback-only 后静默回滚：明确未提交，释放一次
            log.warn("下单事务被标记回滚 userId={} performanceId={} cause={}",
                    userId, performanceId, ex.getMessage());
            releaseAfterRollback(performanceId, userId);
            throw serviceBusy();
        } catch (BusinessException ex) {
            // 业务校验失败（售罄/下架/已开始等）：创建事务已明确回滚，释放一次后原样抛出
            releaseAfterRollback(performanceId, userId);
            throw ex;
        } catch (RuntimeException ex) {
            // 提交结果未知的故障（例如连接在提交时刻中断）：不释放、不重做，
            // 暂停全局准入，返回 503 ORDER_RESULT_UNKNOWN，客户端先查询订单。
            failUnknown(performanceId, userId, ex);
            throw resultUnknown();
        }
    }

    // ------------------------------------------------------------ OCCUPIED：已有占用

    /**
     * 已有占用才查本人有效订单；可复用返回原单，原单已到期则关闭后最多重新预占一次。
     */
    private PlaceOrderVO occupiedAfterReserve(Long userId, Long performanceId) {
        TicketOrder active = orderMapper.selectActiveByUserAndPerformance(userId, performanceId);
        if (active == null) {
            // OCCUPIED 但查不到有效订单：另一次请求正在处理（事务未提交），或缓存残留。
            // 返回处理中，不创建第二单，也不自行清 Redis 标记。
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_PROCESSING",
                    "正在处理中，暂无订单可返回，请稍后查询我的订单");
        }
        PlaceOrderVO reused = tryReuse(active);
        if (reused != null) {
            return reused;
        }
        // 原单是待支付且已到期：最多关闭这一次并重预占一次（不允许对第二张过期单再关闭/再递归）。
        orderCloseService.closeExpired(active.getId());
        OrderReservationResult again = reserveWithAdmissionCheck(userId, performanceId);
        switch (again) {
            case RESERVED:
                return createAfterReserve(userId, performanceId);
            case SOLD_OUT:
                throw new BusinessException(HttpStatus.CONFLICT, "STOCK_INSUFFICIENT", "演出票已售罄");
            case NOT_READY:
                admissionGate.pausePerformance(performanceId);
                throw admissionUnavailable("本场演出数据未就绪，请先查询已有订单");
            case OCCUPIED: {
                // 第二次仍占用：可复用有效原单则返回原单；否则（仍过期/无单）返回处理中，
                // 不再关闭第二张过期单，交给数据库定时扫描处理。
                TicketOrder second = orderMapper.selectActiveByUserAndPerformance(userId, performanceId);
                PlaceOrderVO reusedSecond = tryReuse(second);
                if (reusedSecond != null) {
                    return reusedSecond;
                }
                throw new BusinessException(HttpStatus.CONFLICT, "ORDER_PROCESSING",
                        "正在处理中，请稍后查询我的订单");
            }
            default:
                admissionGate.pausePerformance(performanceId);
                throw admissionUnavailable("预占结果未知，请先查询已有订单");
        }
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
        boolean paid = order.getStatus() == com.zddp.ticket.order.enums.OrderStatus.PAID;
        if (paid || now.isBefore(order.getExpireAt())) {
            return new PlaceOrderVO(false, orderQueryService.toVO(order, now));
        }
        return null;
    }

    // ------------------------------------------------------------ 释放 / 未知结果 / 公用

    /**
     * 建单明确回滚后的 Redis 释放（一次）。释放本身失败时按维护处理：不翻案业务结果。
     */
    private void releaseAfterRollback(Long performanceId, Long userId) {
        try {
            orderReservationStore.release(performanceId, userId);
        } catch (RedisConnectionFailureException ex) {
            log.error("订单明确回滚后释放 Redis 失败（连接故障），暂停全局准入，需人工维护 "
                    + "performanceId={} userId={} cause={}", performanceId, userId, ex.getMessage());
            admissionGate.pauseGlobalAdmission();
        } catch (RuntimeException ex) {
            log.error("订单明确回滚后释放 Redis 失败，暂停该场准入，需人工维护 "
                    + "performanceId={} userId={} cause={}", performanceId, userId, ex.getMessage());
            admissionGate.pausePerformance(performanceId);
        }
    }

    /** 提交结果未知：不释放、不重做；暂停全局准入交人工维护，客户端先查询订单 */
    private void failUnknown(Long performanceId, Long userId, RuntimeException cause) {
        log.error("下单结果未知：不释放 Redis 占用、不自动重做。performanceId={} userId={} cause={}",
                performanceId, userId, cause.getMessage(), cause);
        admissionGate.pauseGlobalAdmission();
    }

    /** 判断这个唯一冲突是不是「同一用户对同一演出已有有效单」。 */
    private boolean isActiveOrderConflict(DuplicateKeyException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause == null ? ex.getMessage() : cause.getMessage();
        return message != null && message.contains(ACTIVE_ORDER_UNIQUE_KEY);
    }

    private BusinessException admissionUnavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_ADMISSION_UNAVAILABLE", message);
    }

    private BusinessException resultUnknown() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_RESULT_UNKNOWN",
                "下单结果未能确认，请先查询我的订单，系统恢复前不要重复提交");
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
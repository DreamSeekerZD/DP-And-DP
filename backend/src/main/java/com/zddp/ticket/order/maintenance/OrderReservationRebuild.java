package com.zddp.ticket.order.maintenance;

import com.zddp.ticket.order.enums.OrderStatus;
import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.model.entity.TicketOrder;
import com.zddp.ticket.order.redis.OrderReservationStore;
import com.zddp.ticket.order.service.OrderCloseService;
import com.zddp.ticket.performance.enums.PerformanceStatus;
import com.zddp.ticket.performance.model.dto.PerformancePurchaseDTO;
import com.zddp.ticket.performance.service.PerformanceInventoryService;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 离线维护：以数据库为准，核对并重建指定演出的 Redis 预占（stock String + occupied ZSet）。
 *
 * <p>必须在<b>停止下单与调度、确认无在途事务</b>后使用（由操作者负责时序，Runner 会校验当前
 * 进程是离线配置）：
 * <ul>
 *   <li><b>audit</b>：只读核对 + 报告该演出已到期待支付订单数量，不写 Redis、不改数据库；</li>
 *   <li><b>apply</b>：先只读一致性校验（不符则零修改）；通过后用数据库时间读取该演出到期待支付
 *       订单、复用 {@link OrderCloseService#closeExpired} 逐单关闭（不复制 SQL 状态机、不处理其他
 *       演出、不启动定时扫描）；到期关闭完成后重新读取库存与有效订单再校验，最后重建两 key
 *       （occupied 先、stock 最后）。</li>
 * </ul>
 *
 * <p>边界：
 * <ul>
 *   <li>允许目标演出是<b>已发布或已下架</b>；已开始也可恢复，仍由正常下单事务拒绝购买。
 *       草稿/不存在明确拒绝，不改演出状态；</li>
 *   <li>维护中 closeExpired 提交后 Redis 释放可能失败（正是重建原因）：以已提交 MySQL 为准，
 *       不为此重试 release；若关闭的<b>数据库事务</b>失败，停止 apply、不写新的 Redis 库存，
 *       报告已完成的合法关闭，不宣称整批回滚；</li>
 *   <li>到期候选只按本演出有界处理，任一步无进展即停止，不无限循环。</li>
 * </ul>
 */
public class OrderReservationRebuild {

    private final OrderMapper orderMapper;

    private final PerformanceInventoryService inventoryService;

    private final StringRedisTemplate redisTemplate;

    private final OrderCloseService orderCloseService;

    public OrderReservationRebuild(OrderMapper orderMapper,
                                   PerformanceInventoryService inventoryService,
                                   StringRedisTemplate redisTemplate,
                                   OrderCloseService orderCloseService) {
        this.orderMapper = orderMapper;
        this.inventoryService = inventoryService;
        this.redisTemplate = redisTemplate;
        this.orderCloseService = orderCloseService;
    }

    /** 执行结果：是否通过核验 + 面向操作者的可读报告行 */
    public static final class Report {
        private boolean consistent = true;
        private final List<String> lines = new ArrayList<String>();

        public boolean isConsistent() {
            return consistent;
        }

        public List<String> lines() {
            return lines;
        }
    }

    /** 只读核对，不写任何 Redis 数据、不关单。 */
    public Report audit(Long performanceId) {
        return execute(performanceId, false);
    }

    /** 核对通过后：关闭到期单（如有）并重建两 key。 */
    public Report apply(Long performanceId) {
        return execute(performanceId, true);
    }

    private Report execute(Long performanceId, boolean apply) {
        Report report = new Report();
        PerformancePurchaseDTO performance = inventoryService.getPurchaseInfo(performanceId);
        if (performance == null) {
            report.consistent = false;
            report.lines.add("演出不存在：performanceId=" + performanceId + "，拒绝操作");
            return report;
        }
        if (performance.getStatus() != PerformanceStatus.PUBLISHED
                && performance.getStatus() != PerformanceStatus.WITHDRAWN) {
            report.consistent = false;
            report.lines.add("演出现状不允许重建（仅已发布/已下架可维护，草稿/其他拒绝）："
                    + "performanceId=" + performanceId + " status=" + performance.getStatus());
            return report;
        }

        int total = performance.getTotalStock();
        int available = performance.getAvailableStock();
        List<TicketOrder> active = orderMapper.selectActiveForReconstruction(performanceId);
        int activeCount = active.size();
        LocalDateTime dbNow = orderMapper.selectDatabaseNowUtc();
        int expiredPending = 0;
        for (TicketOrder order : active) {
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT
                    && !dbNow.isBefore(order.getExpireAt())) {
                expiredPending++;
            }
        }
        report.lines.add("performanceId=" + performanceId + " total=" + total
                + " available=" + available + " effectiveOrders(0/1)=" + activeCount
                + " expiredPending=" + expiredPending
                + " status=" + performance.getStatus());

        boolean rangeOk = available >= 0 && available <= total;
        boolean formulaOk = (total - available) == activeCount;
        report.consistent = rangeOk && formulaOk;
        if (!report.consistent) {
            report.lines.add("核验不符（total-available != 有效订单数，或可售数越界）："
                    + "只报告，不改数据库、不写 Redis，请人工排查后重试");
            return report;
        }
        if (!apply) {
            report.lines.add("只读核对通过（未写 Redis、未改库）；若后续执行 apply，将按数据库时间关闭 "
                    + expiredPending + " 张到期待支付订单后再重建两 key");
            return report;
        }

        // ---------- apply：先关本演出到期待支付单（有界，逐单独立事务） ----------
        int closedCount = applyCloseExpired(performanceId, active, dbNow, report);
        if (!report.isConsistent()) {
            // 关闭的数据库事务失败：停止 apply，不写新的 Redis 库存，但保留已合法关闭的说明
            return report;
        }
        report.lines.add("到期关闭完成：已关闭 " + closedCount + " 张（MySQL 各自独立提交，失败即停止）");

        // ---------- 关闭后重读库存与有效订单，再校验一次 ----------
        PerformancePurchaseDTO after = inventoryService.getPurchaseInfo(performanceId);
        int availAfter = after == null ? 0 : after.getAvailableStock();
        List<TicketOrder> activeAfter = orderMapper.selectActiveForReconstruction(performanceId);
        if (after == null || (total - availAfter) != activeAfter.size()) {
            report.consistent = false;
            report.lines.add("关闭后库存核对不符：不写 Redis，请人工排查");
            return report;
        }
        if (activeAfter.isEmpty() && total != availAfter) {
            report.consistent = false;
            report.lines.add("关闭后应为空场但库存不匹配：不写 Redis，请人工排查");
            return report;
        }

        // ---------- 重建两 key：先 occupied（score=创建时间），最后写 stock ----------
        String occupiedKey = OrderReservationStore.occupiedKey(performanceId);
        String stockKey = OrderReservationStore.stockKey(performanceId);
        redisTemplate.delete(Arrays.asList(occupiedKey, stockKey));
        for (TicketOrder order : activeAfter) {
            long score = order.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
            redisTemplate.opsForZSet().add(occupiedKey, String.valueOf(order.getUserId()), (double) score);
        }
        redisTemplate.opsForValue().set(stockKey, String.valueOf(availAfter));
        report.lines.add("apply 完成：closedExpired=" + closedCount + "，occupied=" + activeAfter.size()
                + "，stock=" + availAfter + "（先写成员、最后写库存）");
        return report;
    }

    /**
     * 逐单调用现有独立事务关闭本演出到期待支付订单。
     * 只在本演出内有界处理；遇到数据库事务失败即停止（不做无限循环），
     * Redis 释放失败以提交后的 MySQL 为准，不重试。
     */
    private int applyCloseExpired(Long performanceId, List<TicketOrder> active, LocalDateTime now, Report report) {
        if (orderCloseService == null) {
            report.consistent = false;
            report.lines.add("维护组件未装配关闭服务，无法执行 apply（生产环境不应出现）");
            return 0;
        }
        int closed = 0;
        for (TicketOrder order : active) {
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                    || now.isBefore(order.getExpireAt())) {
                continue;
            }
            try {
                orderCloseService.closeExpired(order.getId());
                closed++;
            } catch (RuntimeException ex) {
                report.consistent = false;
                report.lines.add("关闭到期单数据库事务失败，停止 apply：orderId=" + order.getId()
                        + "（此前已完成 " + closed + " 张合法关闭并各自提交，不宣称整批回滚）cause="
                        + ex.getMessage());
                return closed;
            }
        }
        return closed;
    }
}
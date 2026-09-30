package com.zddp.ticket.order.job;

import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.order.service.OrderCloseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时补扫：把已到支付截止时间却还挂着的订单关掉，释放被占用的票。
 *
 * <p>定位要说清楚：这是<b>兜底</b>，不是精确的定时器。
 * <ul>
 *   <li>它不是「5 秒内一定释放」的承诺：扫描按固定延迟跑，积压或连续失败会往后拖；</li>
 *   <li>也不依赖内存里的计时器：应用重启后半靠在数据库里补扫，堆积的到期订单会在下一轮被处理；</li>
 *   <li>单实例固定延迟扫描即可，不引入分布式调度、Redis 锁或消息队列——按当前规模这是过度设计。</li>
 * </ul>
 *
 * <p><b>事务边界</b>：扫描本身不开事务，逐条交给 {@link OrderCloseService#closeExpired(Long)}
 * 这个<b>另一个 Bean</b> 的公开事务方法。走别的 Bean 而不是本类的方法，
 * 是为了让 Spring 的事务代理真正生效——自调用会绕过代理，把独立事务变成「跟着调用方走」，
 * 单条失败就会连累整轮。
 *
 * <p>单条关闭失败只记录订单号并继续处理后面的，不中断本轮。
 */
@Component
public class OrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);

    private final OrderMapper orderMapper;

    private final OrderCloseService orderCloseService;

    /** 自动调度开关；集成测试关掉它，改为直接调用 runOnce() */
    private final boolean scanEnabled;

    /** 每轮最多处理多少条，避免一次扫太多把连接占满 */
    private final int scanLimit;

    public OrderTimeoutJob(OrderMapper orderMapper,
                           OrderCloseService orderCloseService,
                           @Value("${ticket.order.timeout-scan-enabled:true}") boolean scanEnabled,
                           @Value("${ticket.order.timeout-scan-limit:100}") int scanLimit) {
        this.orderMapper = orderMapper;
        this.orderCloseService = orderCloseService;
        this.scanEnabled = scanEnabled;
        this.scanLimit = scanLimit;
    }

    /**
     * 定时入口：固定延迟（上一轮结束后再等间隔）执行一轮扫描。
     * 开关关闭时直接返回，不做任何事。
     */
    @Scheduled(fixedDelayString = "${ticket.order.timeout-scan-interval:5s}")
    public void scheduledScan() {
        if (!scanEnabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException ex) {
            // 调度线程抛出异常不会停止后续调度，但会刷堆栈。
            // 这里显式兜住并记录，让「本轮整体失败」也能安静地等到下一轮。
            log.error("订单超时扫描本轮失败", ex);
        }
    }

    /**
     * 扫描一轮：取出已到期但仍待支付的订单号，逐个用独立事务关闭。
     *
     * <p>刻意是 public：集成测试关掉自动调度后直接调用它，
     * 既能验证扫描逻辑，又不用等真实调度周期。
     */
    public void runOnce() {
        List<Long> candidates = orderMapper.selectTimeoutCandidates(scanLimit);
        if (candidates.isEmpty()) {
            return;
        }
        for (Long orderId : candidates) {
            try {
                // 逐单独立事务；单条失败不阻断后面的订单
                orderCloseService.closeExpired(orderId);
            } catch (RuntimeException ex) {
                log.warn("订单超时关闭失败，跳过继续 orderId={} cause={}", orderId, ex.getMessage());
            }
        }
    }
}

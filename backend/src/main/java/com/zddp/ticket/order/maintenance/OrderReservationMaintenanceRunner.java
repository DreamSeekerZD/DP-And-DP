package com.zddp.ticket.order.maintenance;

import com.zddp.ticket.order.mapper.OrderMapper;
import com.zddp.ticket.performance.service.PerformanceInventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 离线维护入口（CommandLineRunner），<b>只在 maintenance profile 下加载</b>。
 *
 * <p>普通 local/test 启动即使误带 {@code app.maintenance.*} 参数，本组件也不会装配，
 * 不重建 Redis、不 System.exit 正常服务（验收 R04）。
 *
 * <p>执行任何维护动作前先校验「本进程满足离线约束」：不是 Web 应用
 * （spring.main.web-application-type=none）且超时自动扫描关闭
 * （ticket.order.timeout-scan-enabled=false）。不满足则以非零码退出且<b>零维护写</b>（验收 R05）。
 * 这校验的是<b>当前进程配置</b>；操作者仍须先停止正常应用、确认无在途请求后才能运行。
 *
 * <p>mode：off 不做任何事；audit 只读核对；apply 核对通过后关闭到期单并重建两 key。
 */
@Component
@Profile("maintenance")
public class OrderReservationMaintenanceRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderReservationMaintenanceRunner.class);

    /** 关闭到期单后的退出码：一致且完成=0；校验不符=2；配置不满足离线约束=3；用法错误=4 */
    static final int EXIT_OK = 0;
    static final int EXIT_INCONSISTENT = 2;
    static final int EXIT_NOT_OFFLINE = 3;
    static final int EXIT_USAGE = 4;

    /** off|audit|apply；默认 off 表示不进入维护流程 */
    @Value("${app.maintenance.mode:off}")
    private String mode;

    /** 维护目标演出编号；必须显式指定 */
    @Value("${app.maintenance.performance-id:0}")
    private long performanceId;

    private final OrderMapper orderMapper;

    private final PerformanceInventoryService inventoryService;

    private final StringRedisTemplate redisTemplate;

    private final com.zddp.ticket.order.service.OrderCloseService orderCloseService;

    private final Environment environment;

    public OrderReservationMaintenanceRunner(OrderMapper orderMapper,
                                             PerformanceInventoryService inventoryService,
                                             StringRedisTemplate redisTemplate,
                                             com.zddp.ticket.order.service.OrderCloseService orderCloseService,
                                             Environment environment) {
        this.orderMapper = orderMapper;
        this.inventoryService = inventoryService;
        this.redisTemplate = redisTemplate;
        this.orderCloseService = orderCloseService;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        String normalizedMode = mode == null ? "off" : mode.trim();
        if (!"audit".equals(normalizedMode) && !"apply".equals(normalizedMode)) {
            return;
        }
        if (!isOfflineApplicationConfig()) {
            log.error("维护入口要求非 Web（spring.main.web-application-type=none）且超时扫描关闭"
                    + "（ticket.order.timeout-scan-enabled=false）。当前配置不满足，拒绝执行维护（零写入），"
                    + "退出码 {}", EXIT_NOT_OFFLINE);
            System.exit(EXIT_NOT_OFFLINE);
        }
        if (performanceId <= 0) {
            log.warn("维护模式需要显式指定 --app.maintenance.performance-id，本次跳过");
            System.exit(EXIT_USAGE);
        }

        OrderReservationRebuild rebuild = new OrderReservationRebuild(
                orderMapper, inventoryService, redisTemplate, orderCloseService);
        OrderReservationRebuild.Report report = "apply".equals(normalizedMode)
                ? rebuild.apply(performanceId)
                : rebuild.audit(performanceId);
        log.info("===== B05 离线维护 {} performanceId={} 结果 =====", normalizedMode, performanceId);
        for (String line : report.lines()) {
            log.info(line);
        }
        log.info("是否通过核验：{}（不通过时禁止继续开放新购买）", report.isConsistent());
        System.exit(report.isConsistent() ? EXIT_OK : EXIT_INCONSISTENT);
    }

    /**
     * 离线约束校验：非 Web 且自动扫描关闭。
     * 提取成可测试方法；只读配置，不产生任何副作用。
     */
    boolean isOfflineApplicationConfig() {
        String webType = environment.getProperty("spring.main.web-application-type", "servlet");
        boolean scanEnabled = Boolean.parseBoolean(
                environment.getProperty("ticket.order.timeout-scan-enabled", "true"));
        return "none".equalsIgnoreCase(webType) && !scanEnabled;
    }
}
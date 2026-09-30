package com.zddp.ticket.performance.model.dto;

import com.zddp.ticket.performance.enums.PerformanceStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 下单所需的演出购买信息，只承载交易判断要用的字段。
 *
 * <p>刻意不含介绍、封面等页面展示字段：下单链路只需要「能不能买、买多少钱、什么时候开始」，
 * 把整个演出实体搬过来会让交易逻辑和展示逻辑纠缠在一起。
 *
 * <p>时间是 UTC 语义的 LocalDateTime，与数据库列一致；比较一律在数据库侧用
 * {@code UTC_TIMESTAMP(3)} 完成，不把它取到应用里再和机器时间比。
 *
 * <p>这是演出模块对外提供的最小交易视图，由 {@code PerformanceInventoryService} 返回，
 * 订单模块不直接访问 performance 表。
 */
@Getter
@Setter
public class PerformancePurchaseDTO {

    /** 演出编号 */
    private Long id;

    /** 演出标题，用于订单成交快照 */
    private String title;

    /** 演出地点，用于订单成交快照 */
    private String venue;

    /** 演出开始时间（UTC），用于订单成交快照与截止时间封顶 */
    private LocalDateTime startsAt;

    /** 票价，单位：分，用于订单成交金额 */
    private Integer priceCent;

    /** 总库存，用于库存恢复的上界判断 */
    private Integer totalStock;

    /** 可售库存，为 0 时不可下单 */
    private Integer availableStock;

    /** 演出状态：只有已发布可下单 */
    private PerformanceStatus status;
}

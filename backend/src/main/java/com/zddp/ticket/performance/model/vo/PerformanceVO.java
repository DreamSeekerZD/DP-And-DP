package com.zddp.ticket.performance.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 演出页面输出对象，公开接口与运营接口共用同一个类型（当前不另建投影 VO）。
 *
 * <p>VO 是页面输出对象，不代表 DDD 的 Value Object。
 *
 * <p>字段语义：
 * <ul>
 *   <li>id / publisherId 是 BIGINT，序列化成十进制字符串，避免前端 Number 截断；</li>
 *   <li>所有时间都带偏移、统一输出 UTC 的 Z 形式；可空时间明确输出 null，不用空串或默认时间；</li>
 *   <li>priceCent 单位是分，保持 JSON 数字；</li>
 *   <li>status 对外是 DRAFT / PUBLISHED / WITHDRAWN，数据库内部是 0/1/2；</li>
 *   <li>serverTime 是本次响应时刻的数据库时间，前端可用它校准「已开始」的判断；</li>
 *   <li>canPurchase 只表达演出层面是否可售，不判断当前用户是否已购买；
 *       本阶段没有购买功能，前端不能据此展示可执行的购买按钮；</li>
 *   <li>unavailableReason 取值 NOT_PUBLISHED / STARTED / SOLD_OUT，可售时为 null；
 *       判断顺序为未发布、已开始、售罄。</li>
 * </ul>
 *
 * <p>绝不包含 passwordHash、Session、CSRF token 等无关或敏感字段。
 */
@Getter
@Setter
public class PerformanceVO {

    /** 演出编号，JSON 中为十进制字符串 */
    private Long id;

    /** 标题；草稿可为 null */
    private String title;

    /** 介绍；草稿可为 null */
    private String description;

    /** 封面地址；可为 null */
    private String coverUrl;

    /** 演出地点；草稿可为 null */
    private String venue;

    /** 开始时间（UTC）；草稿可为 null */
    private OffsetDateTime startsAt;

    /** 票价，单位：分；草稿可为 null */
    private Integer priceCent;

    /** 总库存；草稿可为 null */
    private Integer totalStock;

    /** 可售库存；草稿可为 null */
    private Integer availableStock;

    /** 状态：DRAFT / PUBLISHED / WITHDRAWN */
    private String status;

    /** 所属运营人员编号，JSON 中为十进制字符串 */
    private Long publisherId;

    /** 首次发布时间（UTC）；未发布过为 null */
    private OffsetDateTime publishedAt;

    /** 创建时间（UTC） */
    private OffsetDateTime createdAt;

    /** 最后更新时间（UTC） */
    private OffsetDateTime updatedAt;

    /** 服务端当前时间（UTC），来自数据库，用于判断是否已开始 */
    private OffsetDateTime serverTime;

    /** 演出层面是否可售 */
    private boolean canPurchase;

    /** 不可售原因：NOT_PUBLISHED / STARTED / SOLD_OUT；可售时为 null */
    private String unavailableReason;
}

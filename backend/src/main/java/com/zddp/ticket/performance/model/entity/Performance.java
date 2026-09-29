package com.zddp.ticket.performance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zddp.ticket.performance.enums.PerformanceStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 演出实体，对应表 performance。一条记录代表一场具体演出。
 *
 * <p>时间字段全部是 {@link LocalDateTime}，语义固定为 <b>UTC</b>（数据库列是 DATETIME(3)）。
 * 这里刻意不用带偏移的类型，是为了让「数据库里存的就是 UTC」这件事在实体层没有歧义；
 * 与前端交互时才在请求/响应对象上使用带偏移的 OffsetDateTime，
 * 由 PerformanceConvert 做显式换算，不依赖 JVM 或驱动的默认时区。
 *
 * <p>本实体不作为接口输出，对外统一返回 PerformanceVO。
 */
@Getter
@Setter
@TableName("performance")
public class Performance {

    /** 演出编号，BIGINT 自增；对外输出为十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 标题，去首尾空白后 1—100 字符；草稿可空 */
    private String title;

    /** 介绍，纯文本最多 10000 字符；草稿可空，发布后仍可修改 */
    private String description;

    /** 封面地址，仅 http/https，最多 1024 字符；后端不下载该地址 */
    private String coverUrl;

    /** 演出地点，去首尾空白后 1—200 字符；草稿可空 */
    private String venue;

    /** 开始时间（UTC）；发布时必须晚于数据库当前时间 */
    private LocalDateTime startsAt;

    /** 票价，单位：分；1—100000000，草稿可空 */
    private Integer priceCent;

    /** 总库存；1—1000000，草稿可空，发布后不可修改 */
    private Integer totalStock;

    /** 可售库存；草稿由 totalStock 派生，不得为负或超过总库存 */
    private Integer availableStock;

    /** 状态：草稿 / 已发布 / 已下架 */
    private PerformanceStatus status;

    /** 所属运营人员的用户编号 */
    private Long publisherId;

    /** 首次发布时间（UTC）；仅首次发布写入，重复发布不改变 */
    private LocalDateTime publishedAt;

    /** 创建时间（UTC） */
    private LocalDateTime createdAt;

    /** 最后更新时间（UTC） */
    private LocalDateTime updatedAt;
}

package com.zddp.ticket.performance.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 新建草稿与编辑演出的请求传输对象，POST 与 PUT 共用同一字段集合。
 *
 * <p>DTO 是请求传输对象（本项目约定请求用 DTO，页面输出用 VO），可以直接传给 Service，
 * 本阶段不额外增加 Request→DTO→Command 转换链。
 *
 * <p>PUT 是完整对象替换（不是 PATCH）：请求里出现的字段会覆盖原值，
 * 未出现的字段视为 null。已发布/已下架的演出必须把关键字段原样带回来，
 * 否则会被判定为「试图修改被锁定的字段」而返回 409。
 *
 * <p>不允许出现的字段（publisherId、status、publishedAt、availableStock 等）
 * 一旦出现在请求体里，会被 Jackson 直接拒绝并返回 400——这些值只能由服务端决定。
 *
 * <p>草稿允许字段为空；一旦提供了非空值就必须合法，否则即使是草稿也返回 400。
 * priceCent 与 totalStock 必须是 JSON 整数：浮点形式（含 99.0）一律 400，
 * 不会被静默截断或四舍五入。
 *
 * <p>startsAt 必须带时区偏移（例如 2026-10-05T20:00:00+08:00）。
 * 不带偏移的字符串（例如 2026-10-05T20:00:00）无法反序列化，返回 400，
 * 避免用服务器默认时区去猜用户的意思。
 */
@Getter
@Setter
public class SavePerformanceDTO {

    /** 标题，去首尾空白后 1—100 字符；空白视为未填写 */
    private String title;

    /** 介绍，纯文本最多 10000 字符；空白视为未填写，内部空格与换行保留 */
    private String description;

    /** 封面地址，仅 http/https，最多 1024 字符；空白视为未填写 */
    private String coverUrl;

    /** 演出地点，去首尾空白后 1—200 字符；空白视为未填写 */
    private String venue;

    /** 开始时间，必须带时区偏移；发布时必须晚于数据库当前时间 */
    private OffsetDateTime startsAt;

    /** 票价，单位：分；1—100000000；必须是 JSON 整数 */
    private Integer priceCent;

    /** 总库存；1—1000000；必须是 JSON 整数 */
    private Integer totalStock;
}

package com.zddp.ticket.order.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 运营视角的订单页面输出对象，只读，用于所属运营查询自己演出的订单。
 *
 * <p>与本人 OrderVO 的关键区别是<b>刻意裁剪</b>：运营只关心成交与状态，
 * 因此这里<b>没有</b> ticketNo（票号是购票者的成交凭据）、没有 userId / 用户名 /
 * 其他用户资料，也没有 canPay / canCancel（运营不操作支付）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>id / performanceId 是 BIGINT，序列化成十进制字符串；</li>
 *   <li>amountCent 单位是分，保持 JSON 数字；</li>
 *   <li>status 对外是 PENDING_PAYMENT / PAID / CLOSED，数据库内部是 0/1/2；</li>
 *   <li>closeReason 只有 USER_CANCEL / PAY_TIMEOUT，未关闭为空；</li>
 *   <li>所有时间都带偏移、统一输出 UTC 的 Z 形式；未产生的字段明确输出 null。</li>
 * </ul>
 *
 * <p>不暴露任何用户身份与凭据，也不暴露 activeMarker。
 */
@Getter
@Setter
public class OperatorOrderVO {

    /** 订单编号，JSON 中为十进制字符串 */
    private Long id;

    /** 演出编号，JSON 中为十进制字符串 */
    private Long performanceId;

    /** 成交金额，单位：分 */
    private Integer amountCent;

    /** 状态：PENDING_PAYMENT / PAID / CLOSED */
    private String status;

    /** 关闭原因：USER_CANCEL / PAY_TIMEOUT；未关闭为空 */
    private String closeReason;

    /** 下单时间（UTC） */
    private OffsetDateTime createdAt;

    /** 支付截止时间（UTC） */
    private OffsetDateTime expireAt;

    /** 支付时间（UTC）；未支付为空 */
    private OffsetDateTime paidAt;

    /** 关闭时间（UTC）；未关闭为空 */
    private OffsetDateTime closedAt;
}
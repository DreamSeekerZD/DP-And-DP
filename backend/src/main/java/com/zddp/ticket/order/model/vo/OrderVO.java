package com.zddp.ticket.order.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 订单页面输出对象，本人订单列表与详情共用。
 *
 * <p>字段语义：
 * <ul>
 *   <li>id / performanceId 是 BIGINT，序列化成十进制字符串，避免前端 Number 截断；</li>
 *   <li>演出标题、地点、开始时间与金额都是<b>下单时的成交快照</b>，演出后续改名或下架不影响这里；</li>
 *   <li>所有时间都带偏移、统一输出 UTC 的 Z 形式；未产生的字段明确输出 null；</li>
 *   <li>amountCent 单位是分，保持 JSON 数字；</li>
 *   <li>status 对外是 PENDING_PAYMENT / PAID / CLOSED，数据库内部是 0/1/2；</li>
 *   <li>closeReason 只有 USER_CANCEL / PAY_TIMEOUT，未关闭时为 null；</li>
 *   <li>serverTime 是本次响应时刻的数据库时间，canPay / canCancel 都以它为基准判断，
 *       因此它们是「当前响应时刻」的提示，不能替代写接口的校验；</li>
 *   <li>canPay 表示「待支付且未到截止时间」，只描述资格，不承诺本阶段有支付接口；
 *       canCancel 表示待支付——已到期也允许取消，此时由后端记录 PAY_TIMEOUT。</li>
 * </ul>
 *
 * <p>绝不包含 activeMarker、其他用户的身份信息、Session 或 CSRF token。
 */
@Getter
@Setter
public class OrderVO {

    /** 订单编号，JSON 中为十进制字符串 */
    private Long id;

    /** 演出编号，JSON 中为十进制字符串 */
    private Long performanceId;

    /** 成交快照：演出标题 */
    private String performanceTitle;

    /** 成交快照：演出地点 */
    private String performanceVenue;

    /** 成交快照：演出开始时间（UTC） */
    private OffsetDateTime performanceStartsAt;

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

    /** 票号；B02 不生成，模拟支付阶段才写入，未出票为空 */
    private String ticketNo;

    /** 服务端当前时间（UTC），来自数据库 */
    private OffsetDateTime serverTime;

    /** 当前是否可支付：待支付且 serverTime 早于 expireAt */
    private boolean canPay;

    /** 当前是否可取消：待支付即可（到期也允许，由后端记录 PAY_TIMEOUT） */
    private boolean canCancel;
}

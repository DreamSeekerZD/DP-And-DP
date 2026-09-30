package com.zddp.ticket.order.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zddp.ticket.order.enums.OrderCloseReason;
import com.zddp.ticket.order.enums.OrderStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 订单实体，对应表 ticket_order。
 *
 * <p>时间字段全部是 {@link LocalDateTime}，语义固定为 <b>UTC</b>（数据库列是 DATETIME(3)），
 * 与 B01 的处理方式一致；对外交互时才在 VO 上使用带偏移的 OffsetDateTime。
 *
 * <p>演出标题、地点、开始时间与金额都是<b>下单时的成交快照</b>：
 * 演出之后改名或下架，订单仍然展示当时的成交信息。
 *
 * <p>本实体不作为接口输出，对外统一返回 OrderVO；也不暴露用户编号给其他用户。
 */
@Getter
@Setter
@TableName("ticket_order")
public class TicketOrder {

    /** 订单编号，BIGINT 自增；对外输出为十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 下单用户编号；只能来自服务端登录身份 */
    private Long userId;

    /** 演出编号 */
    private Long performanceId;

    /** 成交快照：下单时的演出标题 */
    private String performanceTitle;

    /** 成交快照：下单时的演出地点 */
    private String performanceVenue;

    /** 成交快照：下单时的演出开始时间（UTC） */
    private LocalDateTime performanceStartsAt;

    /** 成交金额，单位：分；取自演出票价 */
    private Integer amountCent;

    /** 状态：待支付 / 已支付 / 已关闭 */
    private OrderStatus status;

    /** 关闭原因：USER_CANCEL / PAY_TIMEOUT；未关闭为空 */
    private OrderCloseReason closeReason;

    /** 下单时间（UTC） */
    private LocalDateTime createdAt;

    /** 支付截止时间（UTC）：min(下单时间+有效期, 演出开始时间) */
    private LocalDateTime expireAt;

    /** 支付时间（UTC）；未支付为空。B02 不写入 */
    private LocalDateTime paidAt;

    /** 关闭时间（UTC）；未关闭为空 */
    private LocalDateTime closedAt;

    /** 票号；B02 不生成 */
    private String ticketNo;

    /**
     * 有效订单标记，由数据库生成列产出：待支付/已支付为 1，已关闭为 NULL。
     *
     * <p>插入与更新策略都设为 NEVER：这个值只能由数据库算出来，
     * 应用层任何写入都必须被挡住，否则会绕过「一人一演出一张有效单」的唯一约束。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeMarker;
}

package com.zddp.ticket.order.model.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 下单请求传输对象，对应 POST /api/orders。
 *
 * <p>只接受演出编号一个字段。数量固定为一张、金额取自演出票价、用户身份取自服务端会话，
 * 这些都不允许由请求体指定：请求体里出现 userId、amountCent、quantity 之类的字段会被
 * Jackson 直接拒绝并返回 400。
 *
 * <p>编号以十进制字符串传输（JSON 中的 BIGINT 约定）。
 * 非数字、超出 BIGINT、零或负数一律 400；编号存在但用户看不到则按业务规则返回 404 或 409。
 */
@Getter
@Setter
public class CreateOrderDTO {

    /** 演出编号，必填；JSON 中为十进制字符串 */
    private Long performanceId;
}

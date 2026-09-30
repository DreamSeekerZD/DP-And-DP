package com.zddp.ticket.order.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 订单状态。数据库保存 0/1/2，固定映射，不使用 {@code ordinal()}。
 *
 * <p>本阶段（B02）只开放两条转换，都从待支付出发：
 * <ul>
 *   <li>PENDING_PAYMENT -> CLOSED：用户取消或到期关闭，恢复一次库存；</li>
 *   <li>PENDING_PAYMENT -> PAID：模拟支付，属于下一阶段（B03），
 *       B02 没有任何公开写入入口，只在隔离测试里验证终态保护。</li>
 * </ul>
 *
 * <p>PAID 与 CLOSED 是当前终态：不提供退款、不恢复旧单，重买是新建订单。
 */
public enum OrderStatus {

    /** 待支付：占用库存与「一人一演出一张有效单」的唯一性 */
    PENDING_PAYMENT(0),

    /** 已支付：终态；B02 无公开写入入口 */
    PAID(1),

    /** 已关闭：终态；已释放库存，历史保留 */
    CLOSED(2);

    /** 数据库存储值 */
    @EnumValue
    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按数据库数值还原状态。
     * 未知数值说明数据被外部改坏了，直接抛错而不是猜一个默认值。
     */
    public static OrderStatus fromCode(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的订单状态数值: " + code);
    }
}

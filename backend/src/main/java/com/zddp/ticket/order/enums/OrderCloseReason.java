package com.zddp.ticket.order.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 订单关闭原因。数据库以字符串保存（VARCHAR(16)），只有两个约定值，未关闭时为空。
 *
 * <p>两类关闭是有区别的：用户主动取消记 USER_CANCEL；
 * 到了支付截止时间之后才尝试关闭（无论由用户点击还是超时扫描触发）记 PAY_TIMEOUT。
 * 判断依据是关闭动作发生时数据库的当前时间，不是请求发起时间。
 */
public enum OrderCloseReason {

    /** 用户在未到期时主动取消 */
    USER_CANCEL("USER_CANCEL"),

    /** 已到支付截止时间（含时间相等）后关闭 */
    PAY_TIMEOUT("PAY_TIMEOUT");

    /** 数据库存储值 */
    @EnumValue
    private final String code;

    OrderCloseReason(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

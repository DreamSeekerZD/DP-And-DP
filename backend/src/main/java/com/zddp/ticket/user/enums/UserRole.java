package com.zddp.ticket.user.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 用户角色。数据库以字符串保存（VARCHAR(16)），不使用 ordinal，
 * 避免将来枚举顺序调整破坏已有数据。
 *
 * <p>USER：普通用户，浏览、购买并查询凭证；
 * OPERATOR：运营人员，发布并维护自己的演出、查询自己演出的订单。
 *
 * <p>运营角色不默认拥有购买身份，两条链路用不同预置账号验证。
 * 运营账号预置，不开放普通注册成为运营人员。
 */
public enum UserRole {

    /** 普通用户 */
    USER("USER"),

    /** 运营人员 */
    OPERATOR("OPERATOR");

    /** 数据库存储值，同时也是 Spring Security 权限前缀之外的权限名 */
    @EnumValue
    private final String code;

    UserRole(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package com.zddp.ticket.user.model.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 当前用户页面输出对象，用于 GET /api/auth/me 与登录成功的 data。
 *
 * <p>VO 是页面输出对象，不代表 DDD 的 Value Object。
 *
 * <p>只暴露 id、username、role 三项。id 为 BIGINT，序列化成十进制字符串。
 * 密码哈希、会话标识、CSRF token 一律不在此出现。
 */
@Getter
@Setter
public class CurrentUserVO {

    /** 用户编号，JSON 中为十进制字符串 */
    private Long id;

    /** 登录名 */
    private String username;

    /** 角色名：USER 或 OPERATOR */
    private String role;

    public CurrentUserVO() {
    }

    public CurrentUserVO(Long id, String username, String role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }
}

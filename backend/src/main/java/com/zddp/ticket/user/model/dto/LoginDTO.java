package com.zddp.ticket.user.model.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 登录请求传输对象，对应 POST /api/auth/login。
 *
 * <p>DTO 是请求传输对象（本项目约定请求用 DTO，页面输出用 VO），
 * 可以直接传给 Service，本阶段不额外增加 Request→DTO→Command 转换链。
 *
 * <p>两个字段都必填；username 去首尾空白后最长 64 字符，password 最长 128 字符且不做 trim
 * （密码中的空白是有意义的字符）。格式不合法返回 400 VALIDATION_ERROR，
 * 格式合法但账号或密码错误返回 401 INVALID_CREDENTIALS。
 *
 * <p>未知字段会被 Jackson 拒绝（fail-on-unknown-properties=true），
 * 前端不能通过请求体注入角色或用户编号。
 */
@Getter
@Setter
public class LoginDTO {

    /** 登录名 */
    private String username;

    /** 密码明文，仅用于本次比对，不落库、不写日志 */
    private String password;
}

package com.zddp.ticket.user.model.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * CSRF token 页面输出对象，用于 GET /api/auth/csrf。
 *
 * <p>VO 是页面输出对象，不代表 DDD 的 Value Object。
 *
 * <p>token 由 Spring Security 的 HttpSessionCsrfTokenRepository 生成并保存在会话中，
 * 不自制 token 系统。前端只在内存中保存该值，所有写请求（含登录、退出）
 * 都要带同名 header，登录成功后必须重新获取（登录会销毁旧 token）。
 */
@Getter
@Setter
public class CsrfVO {

    /** 写请求需要携带的请求头名，当前为 X-CSRF-TOKEN */
    private String headerName;

    /** token 值；仅运行时使用，不得写入日志或提交到仓库 */
    private String token;

    public CsrfVO() {
    }

    public CsrfVO(String headerName, String token) {
        this.headerName = headerName;
        this.token = token;
    }
}

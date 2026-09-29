package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * 安全组件写出 JSON 响应的小工具。
 *
 * <p>认证入口、拒绝处理器、登录成功/失败、退出成功这五个组件都要做同一件事：
 * 设置 HTTP 状态与 UTF-8 的 JSON 内容类型，再用应用统一的 ObjectMapper 写出响应体。
 * 集中在这里避免同一段样板代码重复五遍，也保证五处的编码与内容类型完全一致。
 *
 * <p>只做响应写出，不含任何业务判断。
 */
final class SecurityJsonWriter {

    private SecurityJsonWriter() {
    }

    /**
     * 把响应体写成 JSON。
     *
     * @param objectMapper 应用统一的 ObjectMapper，保证 ID 字符串化等规则一致
     * @param response     HTTP 响应
     * @param status       HTTP 状态
     * @param body         响应体，通常是 Result
     */
    static void write(ObjectMapper objectMapper, HttpServletResponse response, HttpStatus status, Object body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}

package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import java.io.IOException;

/**
 * 退出成功响应：返回 {loggedOut:true}，不重定向。
 *
 * <p>会话销毁与 Cookie 清除由 LogoutFilter 完成；本类只负责响应体。
 * 退出后旧 Cookie 再访问受保护接口会得到 401。
 */
public class JsonLogoutSuccessHandler implements LogoutSuccessHandler {

    private final ObjectMapper objectMapper;

    public JsonLogoutSuccessHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        SecurityJsonWriter.write(objectMapper, response, HttpStatus.OK,
                Result.success(Boolean.TRUE));
    }
}

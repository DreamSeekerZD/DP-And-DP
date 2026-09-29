package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证入口：返回 JSON 401，不跳转 HTML 登录页。
 *
 * <p>覆盖会话空闲超时、重启后会话丢失、未登录直接访问受保护接口等情形。
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        SecurityJsonWriter.write(objectMapper, response, HttpStatus.UNAUTHORIZED,
                Result.<Void>error("UNAUTHENTICATED", "未登录或会话已失效"));
    }
}

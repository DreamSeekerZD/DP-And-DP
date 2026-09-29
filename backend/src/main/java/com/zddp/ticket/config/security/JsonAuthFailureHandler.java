package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * 登录失败响应：统一 401 INVALID_CREDENTIALS。
 *
 * <p>无论账号不存在还是密码错误，响应完全一致，不披露账号是否存在。
 * DaoAuthenticationProvider 默认会把 UsernameNotFoundException 转成 BadCredentialsException，
 * 因此这里不需要（也不应该）按异常子类型区分返回内容。
 */
public class JsonAuthFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;

    public JsonAuthFailureHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        SecurityJsonWriter.write(objectMapper, response, HttpStatus.UNAUTHORIZED,
                Result.<Void>error("INVALID_CREDENTIALS", "用户名或密码错误"));
    }
}

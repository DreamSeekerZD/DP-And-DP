package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * 拒绝访问处理器：区分 CSRF 校验失败与角色权限不足，都返回 JSON 403。
 *
 * <p>CSRF 失败也走这里：CsrfFilter 自己持有一个 AccessDeniedHandler，
 * CsrfConfigurer 会把本对象注入进去，因此缺 token / 错 token 的写请求
 * 最终由本类的分支返回 CSRF_INVALID。CsrfException 是 AccessDeniedException 的子类。
 *
 * <p>按契约，CSRF 校验可能先于认证执行，所以这类请求得到 403 而不是 401，属于预期行为。
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            SecurityJsonWriter.write(objectMapper, response, HttpStatus.FORBIDDEN,
                    Result.<Void>error("CSRF_INVALID", "CSRF token 缺失或无效"));
            return;
        }
        SecurityJsonWriter.write(objectMapper, response, HttpStatus.FORBIDDEN,
                Result.<Void>error("FORBIDDEN", "当前角色无权访问该资源"));
    }
}

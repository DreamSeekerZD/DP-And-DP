package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.common.Result;
import com.zddp.ticket.user.model.dto.LoginDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * JSON 登录过滤器，处理 POST /api/auth/login。
 *
 * <p>为什么不直接用 UsernamePasswordAuthenticationFilter 的默认行为：
 * 默认实现从表单参数读取凭据并重定向，本项目要求 JSON 请求体与 JSON 响应。
 * 也不能用 {@code setAuthenticationConverter(...)}——父类覆写了 attemptAuthentication，
 * 那个 setter 在本类上不会生效，所以这里直接覆写 attemptAuthentication。
 *
 * <p>本类只负责“解析请求体 + 交给框架的 AuthenticationManager”，
 * 不生成任何 token、不自行写会话。认证成功后的会话固定攻击防护与 CSRF 轮换
 * 由 SecurityConfig 显式注入的 SessionAuthenticationStrategy 负责。
 */
public class JsonUsernamePasswordAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    /** 登录名最大长度，超出按参数错误处理 */
    private static final int MAX_USERNAME_LENGTH = 64;

    /** 密码最大长度，超出按参数错误处理 */
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final ObjectMapper objectMapper;

    public JsonUsernamePasswordAuthenticationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        setFilterProcessesUrl("/api/auth/login");
        // 登录只接受 POST；GET /api/auth/login 会落到后续链路而不是在这里被当作登录处理
        setPostOnly(true);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        LoginDTO loginRequest;
        try {
            loginRequest = objectMapper.readValue(request.getInputStream(), LoginDTO.class);
        } catch (IOException ex) {
            // 请求体不是合法的 JSON 对象：非法 JSON、数组、以及未知字段
            //（全局打开了 fail-on-unknown-properties）都在这里以 IOException 子类抛出。
            // 这属于「请求写错了」，返回 400；不能混成 401 凭据错误，
            // 否则调用方无法区分是请求格式问题还是账号密码不对。
            // 只给通用说明：不返回解析堆栈，也不回显请求体里的密码。
            writeValidationError(response, "登录请求体格式不正确");
            return null;
        }
        if (loginRequest == null) {
            // 正文是字面量 null 时 Jackson 正常返回 null 而不抛异常，必须单独判空，
            // 否则下面取字段会直接 NPE 变成 500。
            writeValidationError(response, "登录请求体不能为空");
            return null;
        }

        String username = loginRequest.getUsername() == null ? "" : loginRequest.getUsername().trim();
        // 密码不做 trim：首尾空白属于密码内容
        String password = loginRequest.getPassword() == null ? "" : loginRequest.getPassword();

        // 字段缺失或超长属于请求格式问题，返回 400 而不是 401，
        // 避免把“没填”和“填错了”混成同一种结果。
        if (username.isEmpty() || password.isEmpty()) {
            writeValidationError(response, "用户名和密码不能为空");
            return null;
        }
        if (username.length() > MAX_USERNAME_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            writeValidationError(response, "用户名或密码长度超出限制");
            return null;
        }

        UsernamePasswordAuthenticationToken authRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        setDetails(request, authRequest);
        return getAuthenticationManager().authenticate(authRequest);
    }

    /**
     * 写出 400 响应，调用方随后返回 null。
     * 父类约定：attemptAuthentication 返回 null 表示本过滤器已自行完成响应，不再继续认证流程。
     *
     * <p>attemptAuthentication 的签名只允许抛 AuthenticationException，不能向外抛 IOException，
     * 因此这里把「客户端已断开、响应写不出去」这种极端情况转成 AuthenticationServiceException，
     * 交给统一的失败处理器兜底。
     */
    private void writeValidationError(HttpServletResponse response, String message) {
        try {
            SecurityJsonWriter.write(objectMapper, response, HttpStatus.BAD_REQUEST,
                    Result.<Void>error("VALIDATION_ERROR", message));
        } catch (IOException ex) {
            throw new AuthenticationServiceException("写出登录参数校验响应失败", ex);
        }
    }
}

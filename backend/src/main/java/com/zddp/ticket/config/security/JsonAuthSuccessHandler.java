package com.zddp.ticket.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.common.Result;
import com.zddp.ticket.user.model.AppUserPrincipal;
import com.zddp.ticket.user.model.vo.CurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * 登录成功响应：返回当前用户基本信息，不重定向。
 *
 * <p>只输出 id、username、role，绝不输出密码哈希。
 * 走到这里时框架已经完成会话固定攻击防护（changeSessionId）并把认证上下文写入会话，
 * 且旧 CSRF token 已被销毁——调用方必须重新获取 CSRF token 才能发起下一个写请求。
 */
public class JsonAuthSuccessHandler implements AuthenticationSuccessHandler {

    private final ObjectMapper objectMapper;

    public JsonAuthSuccessHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        // 本应用只有 DatabaseUserDetailsService 一个认证来源，主体类型必然是我们的实现
        AppUserPrincipal appUser = (AppUserPrincipal) authentication.getPrincipal();
        CurrentUserVO body = new CurrentUserVO(
                appUser.getId(), appUser.getUsername(), appUser.getRole().getCode());
        SecurityJsonWriter.write(objectMapper, response, HttpStatus.OK, Result.success(body));
    }
}

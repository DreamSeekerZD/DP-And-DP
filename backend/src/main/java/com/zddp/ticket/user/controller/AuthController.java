package com.zddp.ticket.user.controller;

import com.zddp.ticket.common.Result;
import com.zddp.ticket.user.model.AppUserPrincipal;
import com.zddp.ticket.user.model.vo.CsrfVO;
import com.zddp.ticket.user.model.vo.CurrentUserVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口。
 *
 * <p>本控制器只承载两个查询型接口。另外两个认证接口由 Spring Security 过滤器链实现，
 * 不放在控制器里，因为它们必须走框架的会话固定攻击防护与 CSRF 轮换：
 * <ul>
 *   <li>POST /api/auth/login —— 由 JsonUsernamePasswordAuthenticationFilter 处理（读取 JSON 请求体）；</li>
 *   <li>POST /api/auth/logout —— 由 Spring Security 的 LogoutFilter 处理。</li>
 * </ul>
 *
 * <p>后端不接受客户端指定当前 userId 或 role，身份一律来自服务端会话中的认证上下文。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 获取 CSRF token，匿名可访问。
     *
     * <p>所有写请求（包括登录与退出）都必须带上同名 header。
     * 登录成功时框架会销毁旧 token，因此登录后必须重新调用本接口再发起写请求。
     */
    @GetMapping("/csrf")
    public Result<CsrfVO> csrf(CsrfToken csrfToken) {
        return Result.success(new CsrfVO(csrfToken.getHeaderName(), csrfToken.getToken()));
    }

    /**
     * 查询当前登录用户，返回 id、username、role。
     *
     * <p>未认证的正常受保护 GET 由 SecurityConfig 的 entry point 返回 JSON 401，不跳转 HTML 登录页。
     */
    @GetMapping("/me")
    public ResponseEntity<Result<CurrentUserVO>> me(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof AppUserPrincipal)) {
            // 正常链路由 anyRequest().authenticated() 保证不会走到这里；
            // 防御性返回 401 而不是让类型转换失败变成 500。
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.<CurrentUserVO>error("UNAUTHENTICATED", "未登录或会话已失效"));
        }
        AppUserPrincipal appUser = (AppUserPrincipal) principal;
        CurrentUserVO body = new CurrentUserVO(
                appUser.getId(), appUser.getUsername(), appUser.getRole().getCode());
        return ResponseEntity.ok(Result.success(body));
    }
}

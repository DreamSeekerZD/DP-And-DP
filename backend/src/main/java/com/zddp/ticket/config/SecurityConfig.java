package com.zddp.ticket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zddp.ticket.config.security.JsonAccessDeniedHandler;
import com.zddp.ticket.config.security.JsonAuthFailureHandler;
import com.zddp.ticket.config.security.JsonAuthSuccessHandler;
import com.zddp.ticket.config.security.JsonAuthenticationEntryPoint;
import com.zddp.ticket.config.security.JsonLogoutSuccessHandler;
import com.zddp.ticket.config.security.JsonUsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;

import java.util.Arrays;

/**
 * 安全配置：Session 会话 + CSRF 校验 + JSON 登录/退出。
 *
 * <p>不使用 JWT、Redis 或会话表；会话保存在单应用内存中，不持久化，重启后需要重新登录。
 * 会话空闲 8 小时到期（在 application.yml 里通过 server.servlet.session.timeout 设置），
 * 这是空闲时长而不是绝对登录时长。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 密码编码器：BCrypt。数据库只保存哈希，不保存明文，也不使用可逆加密。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CSRF token 仓库：保存在 HttpSession 中，使用框架标准机制，不自制 token 系统。
     * header 名保持框架默认的 X-CSRF-TOKEN。
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setParameterName("_csrf");
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokenRepository,
            AuthenticationConfiguration authenticationConfiguration) throws Exception {

        AuthenticationManager authenticationManager = authenticationConfiguration.getAuthenticationManager();

        // 使用非 XOR 的请求处理器：控制器把 token 原样返回给前端，前端原样带回，
        // 便于用 .http 集合和 curl 复现，不必处理 BREACH 掩码。
        // setCsrfRequestAttributeName(null) 让 token 在请求处理早期就解析出来，避免延迟加载的时序差异。
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        JsonUsernamePasswordAuthenticationFilter loginFilter =
                new JsonUsernamePasswordAuthenticationFilter(objectMapper);
        loginFilter.setAuthenticationManager(authenticationManager);

        // 关键：用 addFilterBefore 注册的过滤器不会自动获得会话策略与上下文仓库。
        // 若不显式注入，父类默认是 NullAuthenticatedSessionStrategy + RequestAttributeSecurityContextRepository，
        // 后果是既没有会话固定攻击防护，认证上下文也不会写入会话，下一个请求访问 /auth/me 会直接 401。
        loginFilter.setSecurityContextRepository(new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository()));
        loginFilter.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(Arrays.asList(
                // 登录成功后更换会话 ID，防御会话固定攻击（保留会话属性）
                new ChangeSessionIdAuthenticationStrategy(),
                // 登录成功后销毁旧 CSRF token，下一个请求由框架重新签发；
                // 因此前端登录后必须重新获取 CSRF token 才能发起写请求。
                new CsrfAuthenticationStrategy(csrfTokenRepository))));

        loginFilter.setAuthenticationSuccessHandler(new JsonAuthSuccessHandler(objectMapper));
        loginFilter.setAuthenticationFailureHandler(new JsonAuthFailureHandler(objectMapper));

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        // 匿名可获取 CSRF token 与登录
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // 公开演出浏览：列表与详情
                        .requestMatchers(HttpMethod.GET, "/api/performances", "/api/performances/*").permitAll()
                        // 运营接口要求 OPERATOR 角色；普通用户访问得到 403
                        .requestMatchers("/api/operator/**").hasRole("OPERATOR")
                        // 订单接口要求 USER 角色：运营账号被预置为运营身份，
                        // 不默认具备购买能力，因此访问订单接口得到 403 而不是放行。
                        .requestMatchers("/api/orders", "/api/orders/**").hasRole("USER")
                        // 其余接口（含 /auth/me、/auth/logout）必须已认证
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new JsonLogoutSuccessHandler(objectMapper))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                // 全部关闭表单登录、HTTP Basic 与请求缓存：这是纯 JSON 接口，不做页面跳转
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(loginFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

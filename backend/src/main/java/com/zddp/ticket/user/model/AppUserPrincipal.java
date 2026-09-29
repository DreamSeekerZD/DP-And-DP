package com.zddp.ticket.user.model;

import com.zddp.ticket.user.enums.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * 认证主体，由 {@code DatabaseUserDetailsService} 在登录时构造，随 SecurityContext 保存在会话中。
 *
 * <p>单独建这个类而不是复用 {@link com.zddp.ticket.user.model.entity.AppUser}，
 * 是为了让接口层能直接从已认证主体拿到用户编号与角色，无需再查一次数据库，
 * 同时避免把实体（含 passwordHash）暴露到 Web 层。
 *
 * <p>Spring Security 要求的账号状态在本项目全部恒为可用：没有锁定、过期、禁用等业务概念。
 */
public class AppUserPrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    /** 用户编号，对外输出为十进制字符串 */
    private final Long id;

    /** 登录名 */
    private final String username;

    /** BCrypt 密码哈希，仅供框架做密码比对，不序列化 */
    private final String passwordHash;

    /** 角色 */
    private final UserRole role;

    public AppUserPrincipal(Long id, String username, String passwordHash, UserRole role) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /** 权限名统一加 ROLE_ 前缀，与 SecurityConfig 里的 hasRole("OPERATOR") 对应 */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.getCode()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public Long getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }
}

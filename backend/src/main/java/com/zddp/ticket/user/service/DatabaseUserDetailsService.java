package com.zddp.ticket.user.service;

import com.zddp.ticket.user.mapper.UserMapper;
import com.zddp.ticket.user.model.AppUserPrincipal;
import com.zddp.ticket.user.model.entity.AppUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 从数据库加载认证用户。
 *
 * <p>只负责“登录名 → 认证主体”的读取，不负责密码比对：
 * 比对由框架的 DaoAuthenticationProvider 配合 BCryptPasswordEncoder 完成。
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public DatabaseUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = userMapper.selectByUsername(username);
        if (user == null) {
            // 统一抛 UsernameNotFoundException：DaoAuthenticationProvider 默认会把它转成
            // BadCredentialsException，使“账号不存在”和“密码错误”对外表现完全一致，
            // 不向调用方披露账号是否已注册。
            throw new UsernameNotFoundException("账号或密码错误");
        }
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole());
    }
}

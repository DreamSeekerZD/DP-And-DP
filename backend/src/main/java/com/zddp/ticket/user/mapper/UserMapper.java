package com.zddp.ticket.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zddp.ticket.user.model.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表访问。当前阶段只有“按登录名查用户”这一个读操作，没有任何写操作。
 *
 * <p>用 {@code @Mapper}（MyBatis 注解）而不是 MapStruct 的 {@code @Mapper}，
 * 两者同名但包不同；MyBatis-Plus 自动扫描只识别 org.apache.ibatis 这一个，
 * 因此不会误把 convert 包的 MapStruct 接口注册成数据库 Mapper。
 */
@Mapper
public interface UserMapper extends BaseMapper<AppUser> {

    /**
     * 按登录名精确查询用户，供登录认证使用。
     *
     * <p>显式列出字段而不是 SELECT *：将来表结构增加列时，
     * 不会把新增的敏感字段悄悄读进内存。
     */
    @Select("SELECT id, username, password_hash, role, created_at FROM app_user WHERE username = #{username}")
    AppUser selectByUsername(@Param("username") String username);
}

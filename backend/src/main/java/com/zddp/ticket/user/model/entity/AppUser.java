package com.zddp.ticket.user.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zddp.ticket.user.enums.UserRole;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 app_user。
 *
 * <p>本实体只用于后端认证与归属判断，禁止直接作为接口输出：
 * {@code passwordHash} 绝不能序列化给前端，接口一律返回 CurrentUserVO。
 *
 * <p>当前阶段不提供注册、找回密码、删除或修改角色的接口，账号由 SQL 预置。
 */
@Getter
@Setter
@TableName("app_user")
public class AppUser {

    /** 主键，BIGINT 自增；对外输出为十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录名，唯一，最长 64 字符 */
    private String username;

    /** BCrypt 密码哈希，不存明文；不参与任何 JSON 序列化 */
    private String passwordHash;

    /** 角色：USER 或 OPERATOR */
    private UserRole role;

    /** 创建时间（UTC） */
    private LocalDateTime createdAt;
}

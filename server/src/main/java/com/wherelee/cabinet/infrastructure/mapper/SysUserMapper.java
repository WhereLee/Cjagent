package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 后台用户与 RBAC 读取。
 *
 * <p>这里的 SQL 都会被租户拦截器自动加上 {@code tenant_id} 条件（sys_user / sys_role /
 * sys_user_role / sys_role_permission 都不在白名单），所以"同名账号跨租户串号"不可能发生；
 * 而 {@code sys_permission} 是全局字典，join 它不会被加条件。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 按用户名查（配合登录时已设定的租户上下文，实际条件是 username + tenant_id）。 */
    @Select("""
            select id, tenant_id, username, password_hash, real_name, phone, status,
                   last_login_at, create_time, update_time, deleted
            from sys_user
            where username = #{username} and deleted = 0
            """)
    SysUser selectByUsername(@Param("username") String username);

    /** 权限编码集合，用于 Spring Security 的 hasAuthority 判定。 */
    @Select("""
            select distinct p.code
            from sys_permission p
                     join sys_role_permission rp on rp.permission_id = p.id and rp.deleted = 0
                     join sys_user_role ur on ur.role_id = rp.role_id and ur.deleted = 0
                     join sys_role r on r.id = ur.role_id and r.deleted = 0 and r.status = 1
            where ur.user_id = #{userId}
              and p.deleted = 0
              and p.status = 1
            """)
    List<String> selectPermissionCodes(@Param("userId") Long userId);

    @Select("""
            select distinct r.role_code
            from sys_role r
                     join sys_user_role ur on ur.role_id = r.id and ur.deleted = 0
            where ur.user_id = #{userId}
              and r.deleted = 0
              and r.status = 1
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Update("update sys_user set last_login_at = NOW(3) where id = #{userId} and deleted = 0")
    int touchLastLogin(@Param("userId") Long userId);
}

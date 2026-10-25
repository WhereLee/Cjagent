package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.wherelee.cabinet.domain.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            select id, tenant_id, username, password_hash, real_name, phone, status,
                   last_login_at, create_time, update_time, deleted
            from sys_user
            where username = #{username} and deleted = 0
            """)
    SysUser selectByUsername(@Param("username") String username);

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

    @Select("""
            select distinct u.id
            from sys_user u
                     join sys_user_role ur on ur.user_id = u.id and ur.deleted = 0
                     join sys_role r on r.id = ur.role_id and r.deleted = 0 and r.status = 1
            where r.role_code = #{roleCode}
              and u.status = 1
              and u.deleted = 0
            order by u.id
            """)
    List<Long> selectActiveUserIdsByRole(@Param("roleCode") String roleCode);
}

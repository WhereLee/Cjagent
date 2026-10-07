package com.wherelee.cabinet.common.security;

import com.wherelee.cabinet.domain.entity.SysUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 侧的登录主体（后台账号）。
 *
 * <p>{@code authorities} 是<b>已经带好前缀的成品权限串</b>：角色写成 {@code ROLE_OPS}、
 * 权限点写成 {@code system:user:list}，于是 {@code hasRole('OPS')} 与
 * {@code hasAuthority('system:user:list')} 都能直接用，缓存里也只存这一个列表，
 * 不必再区分"要不要加前缀"（拼前缀的位置只在一处，见 AdminPermissionService）。
 *
 * <p>额外带上 userId / tenantId：业务里"当前操作人、当前租户"是高频需求，
 * 从 SecurityContextHolder 取即可。<b>绝不允许</b>让前端传 tenantId 参数决定查谁的数据。
 */
public record LoginUser(SysUser user, List<String> authorities) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isEnabled() {
        return Integer.valueOf(1).equals(user.getStatus());
    }

    public Long userId() {
        return user.getId();
    }

    public Long tenantId() {
        return user.getTenantId();
    }
}

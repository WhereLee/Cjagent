package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.common.security.LoginUser;
import com.wherelee.cabinet.domain.entity.SysUser;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 按用户名装载后台账号（供 DaoAuthenticationProvider 校验口令）。
 *
 * <p>调用前<b>必须已建立租户上下文</b>：sys_user 的用户名只在租户内唯一，
 * 没有租户条件时同名账号会命中多条。登录流程会先用租户编码定位租户再调到这里，
 * 租户上下文缺失时 SQL 会被租户守卫直接拒绝（40301），不会退化成"撞别人的账号"。
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final SysUserMapper userMapper;
    private final AdminPermissionService permissionService;

    public AdminUserDetailsService(SysUserMapper userMapper, AdminPermissionService permissionService) {
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            // 不在这里区分"用户不存在"和"密码错误"：两种情况必须给攻击者同样的响应，
            // 否则账号枚举免费（口令校验的差异在 Provider 层，也统一成 BadCredentials）
            throw new UsernameNotFoundException("账号或口令不正确");
        }
        return new LoginUser(user, permissionService.resolve(user.getId()));
    }
}

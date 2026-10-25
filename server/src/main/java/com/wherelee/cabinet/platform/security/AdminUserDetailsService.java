package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.platform.security.LoginUser;
import com.wherelee.cabinet.domain.entity.SysUser;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

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
            throw new UsernameNotFoundException("账号或口令不正确");
        }
        return new LoginUser(user, permissionService.resolve(user.getId()));
    }
}

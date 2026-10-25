package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.domain.entity.SysUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

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

package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.app.CabinetProperties;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AdminPermissionService implements AuthoritiesResolver {

    private static final Logger log = LoggerFactory.getLogger(AdminPermissionService.class);

    private final SysUserMapper userMapper;
    private final AuthRedisService authRedis;
    private final CabinetProperties properties;

    public AdminPermissionService(SysUserMapper userMapper, AuthRedisService authRedis, CabinetProperties properties) {
        this.userMapper = userMapper;
        this.authRedis = authRedis;
        this.properties = properties;
    }

    @Override
    public List<String> resolve(Long subjectId) {
        List<String> cached = authRedis.cachedAuthorities(subjectId);
        if (cached != null) {
            return cached;
        }

        List<String> authorities = new ArrayList<>();
        userMapper.selectRoleCodes(subjectId).forEach(code -> authorities.add("ROLE_" + code));
        authorities.addAll(userMapper.selectPermissionCodes(subjectId));
        List<String> distinct = authorities.stream().distinct().sorted().toList();

        authRedis.cacheAuthorities(subjectId, distinct, properties.getJwt().getPermissionCacheTtl());
        log.debug("装载后台权限 userId={} 共 {} 项（已写缓存 {}）", subjectId, distinct.size(),
                properties.getJwt().getPermissionCacheTtl());
        return distinct;
    }

    public void evict(Long subjectId) {
        authRedis.evictAuthorities(subjectId);
    }
}

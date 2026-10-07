package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.config.CabinetProperties;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台账号的 RBAC 权限装载（Redis 缓存 + 回源查库）。
 *
 * <p>角色编码统一加 {@code ROLE_} 前缀存进同一个列表，所以 {@code hasRole('OPS')} 和
 * {@code hasAuthority('system:user:list')} 都能命中，缓存也只需存一份。
 *
 * <p>两个必须知道的语义：
 * <ul>
 *   <li>这里的所有 SQL 都在<b>租户上下文里</b>执行（过滤器已按 token 建好上下文），
 *       因此同名的 role_code 在不同租户各自独立；</li>
 *   <li>权限有 TTL（默认 5 分钟）：<b>改角色/改权限后不会立刻生效</b>。
 *       后台的角色维护功能必须调用 {@link #evict} 清理，否则会出现"权限已收回但仍能用"的窗口期。</li>
 * </ul>
 */
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
        // 角色用 hasRole 语义（ROLE_ 前缀），权限点用 hasAuthority 语义（原样编码）
        userMapper.selectRoleCodes(subjectId).forEach(code -> authorities.add("ROLE_" + code));
        authorities.addAll(userMapper.selectPermissionCodes(subjectId));
        List<String> distinct = authorities.stream().distinct().sorted().toList();

        authRedis.cacheAuthorities(subjectId, distinct, properties.getJwt().getPermissionCacheTtl());
        log.debug("装载后台权限 userId={} 共 {} 项（已写缓存 {}）", subjectId, distinct.size(),
                properties.getJwt().getPermissionCacheTtl());
        return distinct;
    }

    /** 角色或权限变更后调用，否则最坏要等一个缓存周期。 */
    public void evict(Long subjectId) {
        authRedis.evictAuthorities(subjectId);
    }
}

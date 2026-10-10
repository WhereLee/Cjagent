package com.wherelee.cabinet.infrastructure.security;

import java.util.List;

/**
 * 把一个账号 ID 换成 Spring Security 用的权限串。
 *
 * <p>抽这个接口是为了让 {@link JwtAuthenticationFilter} 与"具体是哪一端"解耦：
 * 后台走 RBAC 查库（带缓存），用户端是固定角色。同一个过滤器实现服务两条安全链。
 */
public interface AuthoritiesResolver {

    /**
     * @param subjectId 账号 ID（后台是 sys_user.id，用户端是 biz_customer.id）
     * @return 权限串列表；角色需自带 {@code ROLE_} 前缀
     */
    List<String> resolve(Long subjectId);
}

package com.wherelee.cabinet.infrastructure.security;

import java.time.Instant;

/**
 * 验签通过后的 token 内容。
 *
 * <p>{@code jti} 必须保留：注销与刷新轮换都靠它在 Redis 里做黑名单。
 */
public record VerifiedToken(String jti,
                            String end,
                            String tokenType,
                            Long subjectId,
                            Long tenantId,
                            String username,
                            Instant expiresAt) {

    /** 距离过期还有多久；已过期时返回零（不会返回负数）。 */
    public java.time.Duration ttl() {
        long seconds = java.time.Duration.between(Instant.now(), expiresAt).getSeconds();
        return java.time.Duration.ofSeconds(Math.max(seconds, 0));
    }
}

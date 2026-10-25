package com.wherelee.cabinet.platform.security;

import java.time.Instant;

public record Caller(String jti,
                            String end,
                            String tokenType,
                            Long subjectId,
                            Long tenantId,
                            String username,
                            Instant expiresAt) {

    public java.time.Duration ttl() {
        long seconds = java.time.Duration.between(Instant.now(), expiresAt).getSeconds();
        return java.time.Duration.ofSeconds(Math.max(seconds, 0));
    }
}

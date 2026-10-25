package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.app.CabinetProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    static final int MIN_SECRET_BYTES = 32;

    private final CabinetProperties.Jwt config;
    private final SecretKey key;

    public JwtTokenService(CabinetProperties properties) {
        this.config = properties.getJwt();
        String secret = config.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "cabinet.jwt.secret 未配置或长度不足 " + MIN_SECRET_BYTES + " 字节（HS256 要求，见 RFC 8725）。"
                            + "请在 server/.env 里设置 JWT_SECRET。");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public TokenPair issue(String end, Long subjectId, Long tenantId, String username) {
        Instant now = Instant.now();
        return new TokenPair(
                build(end, subjectId, tenantId, username, AuthConstants.TOKEN_TYPE_ACCESS, now, config.getAccessTtl()),
                build(end, subjectId, tenantId, username, AuthConstants.TOKEN_TYPE_REFRESH, now, config.getRefreshTtl()),
                "Bearer",
                config.getAccessTtl().toSeconds(),
                config.getRefreshTtl().toSeconds());
    }

    private String build(String end, Long subjectId, Long tenantId, String username, String type, Instant now, Duration ttl) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuer(config.getIssuer())
                .subject(String.valueOf(subjectId))
                .claim(AuthConstants.CLAIM_END, end)
                .claim(AuthConstants.CLAIM_TENANT_ID, tenantId)
                .claim(AuthConstants.CLAIM_TOKEN_TYPE, type)
                .claim(AuthConstants.CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Caller verify(String token, String expectedEnd, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(config.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String type = claims.get(AuthConstants.CLAIM_TOKEN_TYPE, String.class);
            String end = claims.get(AuthConstants.CLAIM_END, String.class);
            if (!expectedType.equals(type) || !expectedEnd.equals(end)) {
                log.warn("token 用途不符：期望 end={} typ={}，实际 end={} typ={}", expectedEnd, expectedType, end, type);
                throw new BizException(ResultCode.UNAUTHORIZED);
            }

            Number tenantClaim = claims.get(AuthConstants.CLAIM_TENANT_ID, Number.class);
            return new Caller(
                    claims.getId(),
                    end,
                    type,
                    Long.valueOf(claims.getSubject()),
                    tenantClaim == null ? null : tenantClaim.longValue(),
                    claims.get(AuthConstants.CLAIM_USERNAME, String.class),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("token 验签失败: {}", e.getMessage());
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}

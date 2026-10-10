package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.config.CabinetProperties;
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

/**
 * JWT 签发与验签（HS256）。
 *
 * <p>三类约束是这套凭证能被信任的前提，缺一不可：
 * <ul>
 *   <li><b>typ</b>：access 与 refresh 分开。refresh 拿去调业务接口必须被拒绝，
 *       否则长效凭证等同于永久通行证。</li>
 *   <li><b>end</b>：admin 与 mini 分开。客户 token 拿去访问后台接口必须被拒绝。</li>
 *   <li><b>tid</b>：租户写在凭证里，请求进来时据此建立 TenantContext，
 *       绝不允许由前端传 {@code tenantId} 参数决定（那是最典型的越租入口）。</li>
 * </ul>
 *
 * <p>密钥不给默认值：少于 32 字节直接启动失败。HS256 的密钥短于哈希输出长度时，
 * RFC 8725 明确要求拒绝，否则容易被暴力猜解。
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /** HS256 密钥最小长度要求（RFC 8725）。 */
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

    /** 签发一对 token。subject 放账号 ID，租户与端信息放自定义 claim。 */
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

    /**
     * 验签并校验用途（端 + token 类型）。
     *
     * <p>对外只说"凭证无效或已过期"（40100），不把"签名不对/issuer 不对/已过期"分别告诉调用方：
     * 这些差异对攻击者是免费的探针，而对排查者来说服务端日志里有。
     */
    public VerifiedToken verify(String token, String expectedEnd, String expectedType) {
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
            return new VerifiedToken(
                    claims.getId(),
                    end,
                    type,
                    Long.valueOf(claims.getSubject()),
                    tenantClaim == null ? null : tenantClaim.longValue(),
                    claims.get(AuthConstants.CLAIM_USERNAME, String.class),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不符、格式损坏都落在这里；细节只进日志
            log.warn("token 验签失败: {}", e.getMessage());
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}

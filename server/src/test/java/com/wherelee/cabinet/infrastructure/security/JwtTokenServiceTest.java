package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.config.CabinetProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证签发与用途约束的单元测试（不起 Spring，纯逻辑）。
 *
 * <p>重点测的不是"能不能解析"，而是<b>越界使用必须被拒</b>：
 * refresh 当 access 用、mini 的 token 当 admin 用、密钥不符、密钥太短。
 */
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes-long!!";

    private JwtTokenService serviceWith(String secret) {
        CabinetProperties props = new CabinetProperties();
        props.getJwt().setSecret(secret);
        return new JwtTokenService(props);
    }

    private JwtTokenService service() {
        return serviceWith(SECRET);
    }

    @Test
    @DisplayName("签发的 access 能按同一端解析，租户与账号信息还原正确")
    void accessRoundTrip() {
        TokenPair pair = service().issue(AuthConstants.END_ADMIN, 123L, 456L, "ops-admin");

        VerifiedToken access = service().verify(pair.accessToken(), AuthConstants.END_ADMIN,
                AuthConstants.TOKEN_TYPE_ACCESS);

        assertEquals(123L, access.subjectId());
        assertEquals(456L, access.tenantId());
        assertEquals("ops-admin", access.username());
        assertEquals(AuthConstants.END_ADMIN, access.end());
        assertTrue(access.jti() != null && access.jti().length() >= 16, "jti 太短会撞，无法做黑名单");
        assertTrue(access.ttl().getSeconds() > 0, "剩余有效期应为正数");
    }

    @Test
    @DisplayName("refresh 令牌不能当 access 使用")
    void refreshTokenRejectedAsAccess() {
        TokenPair pair = service().issue(AuthConstants.END_ADMIN, 1L, 2L, "u");

        assertThrows(BizException.class, () -> service().verify(pair.refreshToken(),
                AuthConstants.END_ADMIN, AuthConstants.TOKEN_TYPE_ACCESS));
    }

    @Test
    @DisplayName("跨端使用被拒：mini 签发的 token 过不了 admin 链")
    void crossEndRejected() {
        TokenPair pair = service().issue(AuthConstants.END_MINI, 9L, 8L, "rider");

        assertThrows(BizException.class, () -> service().verify(pair.accessToken(),
                AuthConstants.END_ADMIN, AuthConstants.TOKEN_TYPE_ACCESS));
    }

    @Test
    @DisplayName("换密钥就验不过（防止多环境共用一份配置没被发现）")
    void differentSecretRejected() {
        TokenPair pair = serviceWith(SECRET).issue(AuthConstants.END_ADMIN, 1L, 2L, "u");
        JwtTokenService other = serviceWith("another-secret-key-also-32-bytes-long-here!!");

        assertThrows(BizException.class, () -> other.verify(pair.accessToken(),
                AuthConstants.END_ADMIN, AuthConstants.TOKEN_TYPE_ACCESS));
    }

    @Test
    @DisplayName("密钥短于 32 字节直接启动失败（HS256 要求，RFC 8725）")
    void shortSecretFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> serviceWith("too-short"));
        assertTrue(e.getMessage().contains("32"), "报错要把要求写清楚: " + e.getMessage());
    }

    @Test
    @DisplayName("过期令牌被拒（用负 TTL 造一个已过期的）")
    void expiredTokenRejected() {
        CabinetProperties props = new CabinetProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setAccessTtl(java.time.Duration.ofSeconds(-1));
        JwtTokenService expiring = new JwtTokenService(props);

        TokenPair pair = expiring.issue(AuthConstants.END_ADMIN, 1L, 2L, "u");

        assertThrows(BizException.class, () -> expiring.verify(pair.accessToken(),
                AuthConstants.END_ADMIN, AuthConstants.TOKEN_TYPE_ACCESS));
    }
}

package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(TokenIssuer.class);

    private final JwtTokenService jwtTokenService;
    private final AuthRedisService authRedisService;

    public TokenIssuer(JwtTokenService jwtTokenService, AuthRedisService authRedisService) {
        this.jwtTokenService = jwtTokenService;
        this.authRedisService = authRedisService;
    }

    public TokenPair issueAndRemember(String end, Long subjectId, Long tenantId, String username) {
        TokenPair pair = jwtTokenService.issue(end, subjectId, tenantId, username);
        Caller refresh = jwtTokenService.verify(pair.refreshToken(), end, AuthConstants.TOKEN_TYPE_REFRESH);
        authRedisService.rememberRefreshToken(end, subjectId, refresh.jti(), refresh.ttl());
        return pair;
    }

    public TokenPair refresh(String end, String refreshToken) {
        Caller old = jwtTokenService.verify(refreshToken, end, AuthConstants.TOKEN_TYPE_REFRESH);

        if (authRedisService.isBlacklisted(old.jti())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        if (!authRedisService.isCurrentRefreshToken(end, old.subjectId(), old.jti())) {
            log.warn("refresh 非当前有效值，疑似重放：end={} subjectId={} jti={}", end, old.subjectId(), old.jti());
            throw new BizException(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        authRedisService.blacklist(old.jti(), old.ttl());
        return issueAndRemember(end, old.subjectId(), old.tenantId(), old.username());
    }

    public void logout(String end, Caller currentAccess, String refreshToken) {
        authRedisService.blacklist(currentAccess.jti(), currentAccess.ttl());

        if (StringUtils.hasText(refreshToken)) {
            try {
                Caller refresh = jwtTokenService.verify(refreshToken, end, AuthConstants.TOKEN_TYPE_REFRESH);
                authRedisService.blacklist(refresh.jti(), refresh.ttl());
            } catch (BizException e) {
                log.debug("注销时 refresh 已不可用，忽略：end={} subjectId={}", end, currentAccess.subjectId());
            }
        }
        authRedisService.dropRefreshToken(end, currentAccess.subjectId());
        authRedisService.evictAuthorities(currentAccess.subjectId());
    }
}

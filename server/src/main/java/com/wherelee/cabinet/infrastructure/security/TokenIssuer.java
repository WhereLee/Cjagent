package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 两端共用的凭证生命周期：签发、刷新轮换、注销。
 *
 * <p>为什么不把这段逻辑在后台/客户两端各写一遍：刷新与注销是安全关键路径
 * （旧令牌是否及时作废、重放是否被拦），两份实现迟早会漂移成"一端修好、另一端还漏着"。
 * 唯一区别只有 {@code end} 字符串，所以收到一处，两端传自己的 end。
 */
@Service
public class TokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(TokenIssuer.class);

    private final JwtTokenService jwtTokenService;
    private final AuthRedisService authRedisService;

    public TokenIssuer(JwtTokenService jwtTokenService, AuthRedisService authRedisService) {
        this.jwtTokenService = jwtTokenService;
        this.authRedisService = authRedisService;
    }

    /** 签发一对凭证，并把 refresh 登记为"当前有效"。 */
    public TokenPair issueAndRemember(String end, Long subjectId, Long tenantId, String username) {
        TokenPair pair = jwtTokenService.issue(end, subjectId, tenantId, username);
        VerifiedToken refresh = jwtTokenService.verify(pair.refreshToken(), end, AuthConstants.TOKEN_TYPE_REFRESH);
        authRedisService.rememberRefreshToken(end, subjectId, refresh.jti(), refresh.ttl());
        return pair;
    }

    /**
     * 用 refresh 换新的一对，旧 refresh 立即作废（一次性使用）。
     *
     * <p>两道检查缺一不可：黑名单（已注销/已用过）与"是否当前有效值"（被更新后就该失效）。
     * 后者是防重放的关键：攻击者拿到一份旧 refresh 时，用它刷新会被拒绝，
     * 而正常客户端的新值不受影响。
     */
    public TokenPair refresh(String end, String refreshToken) {
        VerifiedToken old = jwtTokenService.verify(refreshToken, end, AuthConstants.TOKEN_TYPE_REFRESH);

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

    /**
     * 注销：access 进黑名单 + refresh 作废 + 清除该端的"当前有效 refresh"。
     *
     * <p>refresh 传不传都可以：传了就一并立刻作废；没传则 refresh 记录被清掉，
     * 它下次刷新时因"不是当前有效值"被拒绝。access 因为黑名单的存在不再可用。
     */
    public void logout(String end, VerifiedToken currentAccess, String refreshToken) {
        authRedisService.blacklist(currentAccess.jti(), currentAccess.ttl());

        if (StringUtils.hasText(refreshToken)) {
            try {
                VerifiedToken refresh = jwtTokenService.verify(refreshToken, end, AuthConstants.TOKEN_TYPE_REFRESH);
                authRedisService.blacklist(refresh.jti(), refresh.ttl());
            } catch (BizException e) {
                // 已经过期/不可用：本来就该失效，注销按成功处理
                log.debug("注销时 refresh 已不可用，忽略：end={} subjectId={}", end, currentAccess.subjectId());
            }
        }
        authRedisService.dropRefreshToken(end, currentAccess.subjectId());
        // 凭证作废了，权限缓存没必要留着（下次登录重新装载，也避免"已注销但缓存还在"的误解）
        authRedisService.evictAuthorities(currentAccess.subjectId());
    }
}

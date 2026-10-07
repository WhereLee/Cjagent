package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.common.security.AuthConstants;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 读取当前凭证主体（切面、审计、限流都用它）。
 *
 * <p>返回 null 的两种正常情况：公开接口匿名访问、以及非 Web 线程（定时任务、MQ 消费）。
 * 所以调用方必须能处理 null，不要在这里默认成"某个系统用户"——
 * 一旦默认，未登录请求就会以系统身份留下审计记录。
 */
public final class AuthenticatedPrincipal {

    private AuthenticatedPrincipal() {
    }

    public static VerifiedToken currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        // AnonymousAuthenticationFilter 塞的匿名对象 principal 是字符串 "anonymousUser"
        Object principal = authentication.getPrincipal();
        return principal instanceof VerifiedToken token && !AuthConstants.TOKEN_TYPE_REFRESH.equals(token.tokenType())
                ? token
                : null;
    }
}

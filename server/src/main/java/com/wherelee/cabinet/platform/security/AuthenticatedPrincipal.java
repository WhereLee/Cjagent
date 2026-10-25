package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.common.security.AuthConstants;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthenticatedPrincipal {

    private AuthenticatedPrincipal() {
    }

    public static Caller currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Caller token && !AuthConstants.TOKEN_TYPE_REFRESH.equals(token.tokenType())
                ? token
                : null;
    }
}

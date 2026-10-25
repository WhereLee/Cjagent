package com.wherelee.cabinet.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class WebUtils {

    private static final String UNKNOWN = "unknown";

    private WebUtils() {
    }

    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !UNKNOWN.equalsIgnoreCase(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        return StringUtils.hasText(real) ? real.trim() : request.getRemoteAddr();
    }
}

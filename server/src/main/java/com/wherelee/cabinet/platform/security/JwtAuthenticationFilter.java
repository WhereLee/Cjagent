package com.wherelee.cabinet.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.platform.security.AuthFailureWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final String end;
    private final JwtTokenService jwtTokenService;
    private final AuthRedisService authRedisService;
    private final AuthoritiesResolver authoritiesResolver;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(String end,
                                   JwtTokenService jwtTokenService,
                                   AuthRedisService authRedisService,
                                   AuthoritiesResolver authoritiesResolver,
                                   ObjectMapper objectMapper) {
        this.end = end;
        this.jwtTokenService = jwtTokenService;
        this.authRedisService = authRedisService;
        this.authoritiesResolver = authoritiesResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(AuthConstants.AUTH_HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(AuthConstants.BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(AuthConstants.BEARER_PREFIX.length()).trim();
        Caller verified;
        try {
            verified = jwtTokenService.verify(token, end, AuthConstants.TOKEN_TYPE_ACCESS);
            if (verified.tenantId() == null) {
                throw new BizException(ResultCode.UNAUTHORIZED, "凭证缺少租户信息");
            }
            if (authRedisService.isBlacklisted(verified.jti())) {
                throw new BizException(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
            }
        } catch (BizException e) {
            SecurityContextHolder.clearContext();
            log.debug("{} 端凭证校验失败: {}", end, e.getMessage());
            AuthFailureWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED.value(),
                    ResultCode.UNAUTHORIZED, e.getMessage());
            return;
        }

        try {
            TenantContext.set(verified.tenantId());
            List<String> authorities = authoritiesResolver.resolve(verified.subjectId());
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    verified, null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}

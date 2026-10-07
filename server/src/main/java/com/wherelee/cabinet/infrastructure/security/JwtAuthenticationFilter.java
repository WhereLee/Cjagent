package com.wherelee.cabinet.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.common.security.AuthFailureWriter;
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

/**
 * JWT 认证过滤器：校验凭证、建立租户上下文、装载权限。
 *
 * <p><b>不是 @Component</b>：这个类要在两条安全链里各建一个实例（end=admin / end=mini）。
 * 标了 @Component 会被 Spring Boot 当成普通 Servlet 过滤器再注册一次，同一请求执行两遍，
 * 而且执行位置跳到 Security 链之外，租户上下文与认证信息会错位。实例化只在 SecurityConfig 里做。
 *
 * <p><b>控制流的两个刻意的写法</b>：
 * <ul>
 *   <li>只有"凭证校验阶段"的 BizException 才转成 401 响应体。业务执行期抛的 BizException
 *       （比如租户守卫拒绝、业务规则不满足）必须原样交给下游的 GlobalExceptionHandler，
 *       所以 {@code chain.doFilter} <b>不在</b>捕获 BizException 的 try 里 ——
 *       图省事包进去会把业务错误统统变成 401。</li>
 *   <li>租户上下文在 {@code chain.doFilter} 前设置、在它的 finally 里清理，
 *       这样整条业务调用都看得见；不清理就会把上一个请求的租户带给下一个请求（线程池复用）。
 *       权限装载依赖租户上下文，所以顺序必须是 验签 → 建上下文 → 查权限。</li>
 * </ul>
 */
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
            // 没带凭证就放行到下游，由 authorizeHttpRequests 决定要不要认证（公开接口可匿名访问）
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(AuthConstants.BEARER_PREFIX.length()).trim();
        VerifiedToken verified;
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
            // 具体原因（过期/签名不符/已注销）只进日志，响应里统一 40100，不给攻击者探针
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

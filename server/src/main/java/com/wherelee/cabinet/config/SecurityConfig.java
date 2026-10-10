package com.wherelee.cabinet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.common.security.AuthFailureWriter;
import com.wherelee.cabinet.infrastructure.security.AdminPermissionService;
import com.wherelee.cabinet.infrastructure.security.AuthRedisService;
import com.wherelee.cabinet.infrastructure.security.JwtAuthenticationFilter;
import com.wherelee.cabinet.infrastructure.security.JwtTokenService;
import com.wherelee.cabinet.infrastructure.security.CustomerAuthoritiesResolver;
import com.wherelee.cabinet.infrastructure.security.AdminUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 三条安全链：公开、后台、用户端。
 *
 * <p><b>为什么要按端拆成独立的链，而不是一条链里写一堆 requestMatchers</b>：
 * 两端的凭证互不通用是安全要求（客户 token 不能访问后台），拆链后每条链只认自己端的 token，
 * 过滤器里的 {@code end} 断言天然形成隔离；混在一条链里靠路径猜，漏一条规则就是一个洞。
 *
 * <p>链的顺序由 {@code @Order} 固定：公开的（自检/文档/探针）在最前面，
 * 否则 {@code /api/system/**} 会被后面的 {@code /api/**} 规则吃掉变成 401。
 *
 * <p>CSRF 关闭的前提是<b>完全无状态 + 凭证走 Authorization 头</b>：浏览器不会自动带上
 * 这个头，所以跨站请求伪造拿不到凭证。如果哪天改成 Cookie 存 token，这个判断立刻失效，
 * CSRF 必须重新打开。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 公开链：健康自检、探针、文档、actuator。
     *
     * <p>{@code /actuator/**} 整段放在这里是安全的，因为 prod 的
     * {@code management.endpoints.web.exposure.include} 只留 health,info ——
     * 未露出的端点根本不存在，比“靠安全规则拦住它”可靠（规则会漏，白名单不会）。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain publicChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.securityMatcher("/api/system/**", "/api/public/**", "/actuator/**",
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                        AuthFailureWriter.write(res, objectMapper, HttpStatus.UNAUTHORIZED.value(),
                                ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage())));
        return http.build();
    }

    /** 后台端：账密登录换 token，权限走 RBAC。 */
    @Bean
    @Order(2)
    public SecurityFilterChain adminChain(HttpSecurity http,
                                          JwtTokenService jwtTokenService,
                                          AuthRedisService authRedisService,
                                          AdminPermissionService permissionService,
                                          ObjectMapper objectMapper) throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                AuthConstants.END_ADMIN, jwtTokenService, authRedisService, permissionService, objectMapper);

        http.securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // 登录与刷新必须匿名可达，否则拿不到 token 就无法换取 token
                        .requestMatchers("/api/admin/auth/login", "/api/admin/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> AuthFailureWriter.write(res, objectMapper,
                                HttpStatus.UNAUTHORIZED.value(), ResultCode.UNAUTHORIZED,
                                ResultCode.UNAUTHORIZED.getMessage()))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 用户端（寄存客户）：小程序 code 换 openid 再换 token，固定角色。 */
    @Bean
    @Order(3)
    public SecurityFilterChain miniChain(HttpSecurity http,
                                         JwtTokenService jwtTokenService,
                                         AuthRedisService authRedisService,
                                         CustomerAuthoritiesResolver customerAuthoritiesResolver,
                                         ObjectMapper objectMapper) throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                AuthConstants.END_MINI, jwtTokenService, authRedisService, customerAuthoritiesResolver, objectMapper);

        http.securityMatcher("/api/mini/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // login / refresh 必须匿名可达：access 过期时客户端手里只有 refresh
                        .requestMatchers("/api/mini/auth/login", "/api/mini/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> AuthFailureWriter.write(res, objectMapper,
                                HttpStatus.UNAUTHORIZED.value(), ResultCode.UNAUTHORIZED,
                                ResultCode.UNAUTHORIZED.getMessage()))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 兜底链：以上都没匹配上的路径一概要求已认证。
     *
     * <p>为什么必需：多个 {@code securityMatcher} 的链只处理自己匹配的路径，
     * 没匹上的请求会绕过整套安全过滤直接进应用——新加一个接口、写错一个路径前缀，
     * 它就是个无鉴权入口。默认拒绝能把这类坑位从“静默暴露”变成“401，立即看得见”。
     */
    @Bean
    @Order(4)
    public SecurityFilterChain catchAllChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> AuthFailureWriter.write(res, objectMapper,
                                HttpStatus.UNAUTHORIZED.value(), ResultCode.UNAUTHORIZED,
                                ResultCode.UNAUTHORIZED.getMessage()))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)));
        return http.build();
    }

    /**
     * 授权失败也回统一响应体。
     *
     * <p>注意区分：401 是"我不知道你是谁"（未认证/凭证失效），403 是"我知道你是谁但你不行"
     * （已认证、权限不足）。把两者都写成 401 会让前端的"跳登录"逻辑在权限不足时错误触发。
     */
    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (req, res, ex) -> AuthFailureWriter.write(res, objectMapper, HttpStatus.FORBIDDEN.value(),
                ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.getMessage());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt：自带盐、可调工作因子。绝不用 MD5/SHA1，也不用裸 MD5+固定盐
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AdminUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // hideUserNotFoundExceptions 默认为 true：账号不存在与口令错误返回同样的 BadCredentials，
        // 不做这个保护就等于开放账号枚举接口
        return new ProviderManager(provider);
    }
}

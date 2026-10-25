package com.wherelee.cabinet.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.platform.security.AuthFailureWriter;
import com.wherelee.cabinet.platform.security.AdminPermissionService;
import com.wherelee.cabinet.platform.security.AuthRedisService;
import com.wherelee.cabinet.platform.security.JwtAuthenticationFilter;
import com.wherelee.cabinet.platform.security.JwtTokenService;
import com.wherelee.cabinet.identity.app.CustomerAuthoritiesResolver;
import com.wherelee.cabinet.platform.security.AdminUserDetailsService;
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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

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

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (req, res, ex) -> AuthFailureWriter.write(res, objectMapper, HttpStatus.FORBIDDEN.value(),
                ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.getMessage());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AdminUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}

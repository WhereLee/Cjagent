package com.wherelee.cabinet.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：管理后台与 uni-app H5 都在别的端口上，第一个联调请求就会撞 preflight。
 *
 * <p>用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins}：前者支持
 * {@code https://*.example.com} 这类通配，且能与 {@code allowCredentials(true)} 共存
 * （后者的组合用 allowedOrigins("*") 会在运行期抛 IllegalArgumentException）。
 *
 * <p>白名单为空时<b>不注册任何映射</b>，等于关闭 CORS：prod 忘配域名时宁可拒绝跨域请求，
 * 也不要退化成放行任意来源。
 */
@Configuration
@EnableConfigurationProperties(CabinetProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final CabinetProperties properties;

    public CorsConfig(CabinetProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var cors = properties.getCors();
        if (cors.getAllowedOriginPatterns().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(cors.getAllowedOriginPatterns().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(cors.getExposedHeaders().toArray(String[]::new))
                .allowCredentials(true)
                .maxAge(cors.getMaxAge());
    }
}

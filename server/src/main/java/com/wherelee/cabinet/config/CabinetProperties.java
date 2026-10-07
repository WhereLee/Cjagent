package com.wherelee.cabinet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code cabinet.*} 下需要类型安全绑定的配置项。
 *
 * <p>这里只收 cors 与 access-log；{@code cabinet.datasource.*} 是直接绑到 Hikari 的
 * （见 MysqlDataSourceConfig / PgDataSourceConfig），{@code cabinet.exception.*} 由
 * GlobalExceptionHandler 用 @Value 取，两者都不必在这里重复声明。
 * 默认 ignoreUnknownFields=true，所以同一个前缀下有其他键不会报错。
 */
@ConfigurationProperties(prefix = "cabinet")
public class CabinetProperties {

    private final Cors cors = new Cors();
    private final AccessLog accessLog = new AccessLog();

    public Cors getCors() {
        return cors;
    }

    public AccessLog getAccessLog() {
        return accessLog;
    }

    /** 跨域白名单：前端本地端口与正式域名分开配，绝不用 * 配 allowCredentials。 */
    public static class Cors {
        /** 允许的 Origin 模式，如 http://localhost:5173。空列表表示完全关闭 CORS。 */
        private List<String> allowedOriginPatterns = new ArrayList<>();

        /** 暴露给前端读的响应头（traceId 靠它才能在前端控制台里拿到）。 */
        private List<String> exposedHeaders = new ArrayList<>(List.of("X-Trace-Id"));

        /** 预检结果缓存秒数，避免每个请求都打一次 OPTIONS。 */
        private long maxAge = 3600L;

        public List<String> getAllowedOriginPatterns() {
            return allowedOriginPatterns;
        }

        public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
            this.allowedOriginPatterns = allowedOriginPatterns;
        }

        public List<String> getExposedHeaders() {
            return exposedHeaders;
        }

        public void setExposedHeaders(List<String> exposedHeaders) {
            this.exposedHeaders = exposedHeaders;
        }

        public long getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(long maxAge) {
            this.maxAge = maxAge;
        }
    }

    /** 访问日志与慢请求阈值。 */
    public static class AccessLog {
        private boolean enabled = true;
        /** 超过该耗时按 warn 记录，单位毫秒。 */
        private long slowThresholdMs = 500L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }
    }
}

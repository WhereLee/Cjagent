package com.wherelee.cabinet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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
    private final Tenant tenant = new Tenant();
    private final Jwt jwt = new Jwt();
    private final Mini mini = new Mini();

    public Cors getCors() {
        return cors;
    }

    public AccessLog getAccessLog() {
        return accessLog;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Mini getMini() {
        return mini;
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

    /**
     * 多租户。没有 enabled 开关：关掉就等于没有隔离，这种能力不应该能被配置误关。
     */
    public static class Tenant {
        /** 租户列名，全库统一。 */
        private String column = "tenant_id";

        /**
         * 追加白名单（内置的 flyway_schema_history / sys_tenant / sys_operation_log 无需重复写）。
         * 典型是全局字典、区域、套餐模板这类平台维护的表。
         */
        private List<String> ignoreTables = new ArrayList<>();

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public List<String> getIgnoreTables() {
            return ignoreTables;
        }

        public void setIgnoreTables(List<String> ignoreTables) {
            this.ignoreTables = ignoreTables;
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

    /**
     * JWT 签发与校验。{@code secret} 不给默认值：缺了就启动失败，
     * 避免带默认密钥上线（这种密钥一旦写进代码就等于公开）。
     */
    public static class Jwt {
        /** HS256 要求至少 32 字节。 */
        private String secret;
        private String issuer = "cabinet-server";
        private Duration accessTtl = Duration.ofMinutes(30);
        private Duration refreshTtl = Duration.ofDays(7);
        /** 权限清单缓存时长：角色变更后最坏 5 分钟内旧权限仍生效。 */
        private Duration permissionCacheTtl = Duration.ofMinutes(5);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Duration getAccessTtl() {
            return accessTtl;
        }

        public void setAccessTtl(Duration accessTtl) {
            this.accessTtl = accessTtl;
        }

        public Duration getRefreshTtl() {
            return refreshTtl;
        }

        public void setRefreshTtl(Duration refreshTtl) {
            this.refreshTtl = refreshTtl;
        }

        public Duration getPermissionCacheTtl() {
            return permissionCacheTtl;
        }

        public void setPermissionCacheTtl(Duration permissionCacheTtl) {
            this.permissionCacheTtl = permissionCacheTtl;
        }
    }

    /**
     * 小程序端登录配置。{@code mockLogin} 只在 dev 生效：
     * 没有 AppID 时把 code 直接换成一个固定 openid，便于本地跑通完整链路；
     * prod 必须为 false，否则等于开了一个无需微信验签的后门。
     */
    public static class Mini {
        private boolean mockLogin = false;
        private String appId;
        private String appSecret;
        private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

        public boolean isMockLogin() {
            return mockLogin;
        }

        public void setMockLogin(boolean mockLogin) {
            this.mockLogin = mockLogin;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getCode2SessionUrl() {
            return code2SessionUrl;
        }

        public void setCode2SessionUrl(String code2SessionUrl) {
            this.code2SessionUrl = code2SessionUrl;
        }
    }
}

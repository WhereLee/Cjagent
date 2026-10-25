package com.wherelee.cabinet.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    public static class Cors {
        private List<String> allowedOriginPatterns = new ArrayList<>();

        private List<String> exposedHeaders = new ArrayList<>(List.of("X-Trace-Id"));

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

    public static class Tenant {
        private String column = "tenant_id";

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

    public static class AccessLog {
        private boolean enabled = true;
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

    public static class Jwt {
        private String secret;
        private String issuer = "cabinet-server";
        private Duration accessTtl = Duration.ofMinutes(30);
        private Duration refreshTtl = Duration.ofDays(7);
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

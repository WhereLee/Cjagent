package com.wherelee.cabinet.common.security;

/**
 * 认证授权相关的常量：键名与请求头一处定义，避免各处拼字符串。
 *
 * <p>Redis 键统一 {@code cab:auth:} 前缀（本机 Redis 是多项目共用实例，且用的是 db8）。
 */
public final class AuthConstants {

    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /** JWT 自定义 claim 名。 */
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_END = "end";
    public static final String CLAIM_USERNAME = "usr";

    /** token 类型取值：access 只能调业务接口，refresh 只能用于换新。 */
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** 两端标识：跨端使用别人的 token 必须被拒绝。 */
    public static final String END_ADMIN = "admin";
    public static final String END_MINI = "mini";

    /** Redis 键前缀。 */
    public static final String KEY_BLACKLIST = "cab:auth:blacklist:";
    public static final String KEY_REFRESH = "cab:auth:refresh:";
    public static final String KEY_PERMISSION_CACHE = "cab:auth:perm:";

    private AuthConstants() {
    }
}

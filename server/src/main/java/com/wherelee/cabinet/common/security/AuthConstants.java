package com.wherelee.cabinet.common.security;

public final class AuthConstants {

    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_END = "end";
    public static final String CLAIM_USERNAME = "usr";

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    public static final String END_ADMIN = "admin";
    public static final String END_MINI = "mini";

    public static final String KEY_BLACKLIST = "cab:auth:blacklist:";
    public static final String KEY_REFRESH = "cab:auth:refresh:";
    public static final String KEY_PERMISSION_CACHE = "cab:auth:perm:";

    private AuthConstants() {
    }
}

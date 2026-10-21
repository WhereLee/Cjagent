package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.platform.security.TokenPair;

/**
 * 后台认证入口（登录、刷新、注销、当前账号）。
 *
 * <p>放在 api 而不是让 BFF 注入 {@code AdminAuthService}：认证的实现会继续拆
 * （令牌轮换、锁定、租户校验），BFF 不应跟着改包名；跨服务只认 api 是硬门禁。
 */
public interface AdminAuthPort {

    TokenPair login(AdminLoginRequest request);

    TokenPair refresh(String refreshToken);

    void logout(Caller currentAccess, String refreshToken);

    CurrentAccountView me(Caller current);
}

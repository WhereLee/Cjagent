package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.platform.security.TokenPair;

/**
 * 用户端认证入口（小程序登录、刷新、注销、当前资料）。
 *
 * <p>登录涉及外部通道（微信 code → openid）、首次注册与租户归属，这些都属于身份服务；
 * BFF 只做协议转换（code 入参、token 出参），不碰其中任何一条规则。
 */
public interface CustomerAuthPort {

    CustomerLoginView login(CustomerLoginRequest request);

    TokenPair refresh(String refreshToken);

    void logout(Caller currentAccess, String refreshToken);

    CustomerView currentProfile(Caller current);
}

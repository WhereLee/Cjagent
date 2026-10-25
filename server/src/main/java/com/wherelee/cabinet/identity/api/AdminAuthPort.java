package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.platform.security.TokenPair;

public interface AdminAuthPort {

    TokenPair login(AdminLoginRequest request);

    TokenPair refresh(String refreshToken);

    void logout(Caller currentAccess, String refreshToken);

    CurrentAccountView me(Caller current);
}

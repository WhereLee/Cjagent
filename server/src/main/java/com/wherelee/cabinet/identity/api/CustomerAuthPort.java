package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.platform.security.TokenPair;

public interface CustomerAuthPort {

    CustomerLoginView login(CustomerLoginRequest request);

    TokenPair refresh(String refreshToken);

    void logout(Caller currentAccess, String refreshToken);

    CustomerView currentProfile(Caller current);
}

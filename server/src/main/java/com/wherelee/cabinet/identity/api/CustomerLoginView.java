package com.wherelee.cabinet.identity.api;

public record CustomerLoginView(String accessToken,
                                String refreshToken,
                                String tokenType,
                                long expiresIn,
                                long refreshExpiresIn,
                                Long customerId,
                                Long tenantId,
                                boolean newRegister) {
}

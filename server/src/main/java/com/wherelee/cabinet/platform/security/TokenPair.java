package com.wherelee.cabinet.platform.security;

public record TokenPair(String accessToken,
                        String refreshToken,
                        String tokenType,
                        long accessExpiresIn,
                        long refreshExpiresIn) {
}

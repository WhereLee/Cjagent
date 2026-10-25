package com.wherelee.cabinet.bff.mini.auth.dto;

public record MiniTokenView(String accessToken,
                            String refreshToken,
                            String tokenType,
                            long expiresIn,
                            long refreshExpiresIn) {
}

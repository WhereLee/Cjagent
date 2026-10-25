package com.wherelee.cabinet.bff.admin.auth.dto;

public record TokenView(String accessToken,
                        String refreshToken,
                        String tokenType,
                        long expiresIn,
                        long refreshExpiresIn) {
}

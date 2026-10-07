package com.wherelee.cabinet.infrastructure.security;

/**
 * 签发结果：一对 token + 各自有效期（秒）。
 *
 * <p>返回给前端的 {@code expiresIn} 用秒而不是绝对时间戳，避免客户端与服务端时钟不一致
 * 导致提前/延后刷新。
 */
public record TokenPair(String accessToken,
                        String refreshToken,
                        String tokenType,
                        long accessExpiresIn,
                        long refreshExpiresIn) {
}

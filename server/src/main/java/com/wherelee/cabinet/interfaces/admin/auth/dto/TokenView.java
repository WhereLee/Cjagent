package com.wherelee.cabinet.interfaces.admin.auth.dto;

/**
 * 给前端的凭证响应。
 *
 * <p>{@code expiresIn} 是<b>秒数</b>不是绝对时间：客户端据此提前一点刷新，
 * 不受客户端与服务端时钟差的影响。
 */
public record TokenView(String accessToken,
                        String refreshToken,
                        String tokenType,
                        long expiresIn,
                        long refreshExpiresIn) {
}

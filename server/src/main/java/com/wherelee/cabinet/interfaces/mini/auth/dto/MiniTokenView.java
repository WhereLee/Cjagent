package com.wherelee.cabinet.interfaces.mini.auth.dto;

/**
 * 用户端刷新后的凭证响应（不含身份字段，前端登录时已经拿到过 riderId/tenantId）。
 *
 * <p>与 {@code MiniLoginView} 分开定义，是为了让"刷新"这个响应不至于带着
 * {@code newRegister=false} 这种毫无意义的字段——两端各自演进，互不牵制。
 */
public record MiniTokenView(String accessToken,
                            String refreshToken,
                            String tokenType,
                            long expiresIn,
                            long refreshExpiresIn) {
}

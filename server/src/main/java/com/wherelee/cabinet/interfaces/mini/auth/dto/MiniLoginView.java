package com.wherelee.cabinet.interfaces.mini.auth.dto;

/**
 * 骑手端登录响应：凭证 + 账号身份 + 是否新注册。
 *
 * <p>{@code newRegister} 给前端决定要不要跳去补资料（手机号/实名）；
 * 只影响引导流程，不参与任何权限判定。
 */
public record MiniLoginView(String accessToken,
                            String refreshToken,
                            String tokenType,
                            long expiresIn,
                            long refreshExpiresIn,
                            Long riderId,
                            Long tenantId,
                            boolean newRegister) {
}

package com.wherelee.cabinet.application.auth.dto;

/**
 * 用户端登录响应：凭证 + 账号身份 + 是否新注册。
 *
 * <p>{@code newRegister} 只影响前端引导流程（要不要选运营商/扫码），<b>不参与任何权限判定</b>——
 * 把它当授权依据是典型的逻辑漏洞。
 *
 * <p>身份字段以字符串回传（雪花 ID 超 JS 安全整数，见 JacksonConfig 的全局约定）。
 */
public record CustomerLoginView(String accessToken,
                                String refreshToken,
                                String tokenType,
                                long expiresIn,
                                long refreshExpiresIn,
                                Long customerId,
                                Long tenantId,
                                boolean newRegister) {
}

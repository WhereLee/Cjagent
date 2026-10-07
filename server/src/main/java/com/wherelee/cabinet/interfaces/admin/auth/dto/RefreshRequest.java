package com.wherelee.cabinet.interfaces.admin.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新入参：只需要 refresh 令牌本身。
 *
 * <p>账号与租户信息都在令牌里，不再从请求参数取——凡是"前端说自己是谁"的入参，
 * 都是越权设计的起点。
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}

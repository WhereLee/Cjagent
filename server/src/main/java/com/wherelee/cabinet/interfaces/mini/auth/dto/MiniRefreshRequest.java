package com.wherelee.cabinet.interfaces.mini.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 用户端刷新入参：只带 refresh 令牌，身份全部从令牌里取。 */
public record MiniRefreshRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}

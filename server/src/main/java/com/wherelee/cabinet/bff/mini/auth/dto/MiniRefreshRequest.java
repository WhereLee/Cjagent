package com.wherelee.cabinet.bff.mini.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record MiniRefreshRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}

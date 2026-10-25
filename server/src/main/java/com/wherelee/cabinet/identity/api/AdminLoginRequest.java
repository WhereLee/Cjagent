package com.wherelee.cabinet.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(

        @NotBlank(message = "租户编码不能为空")
        @Size(max = 64, message = "租户编码过长")
        String tenantCode,

        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名过长")
        String username,

        @NotBlank(message = "口令不能为空")
        @Size(min = 6, max = 64, message = "口令长度应在 6-64 之间")
        String password) {
}

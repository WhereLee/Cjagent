package com.wherelee.cabinet.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerLoginRequest(

        @NotBlank(message = "登录凭证 code 不能为空")
        @Size(max = 128, message = "code 过长")
        String code,

        @Size(max = 64, message = "租户编码过长")
        String tenantCode,

        @Size(max = 20, message = "手机号长度不正确")
        String phone) {
}

package com.wherelee.cabinet.interfaces.mini.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 骑手端登录入参。
 *
 * <p>{@code tenantCode} 只在<b>首次注册</b>时必填；已有账号可以不传（沿用账号自身的归属）。
 * 这是当前阶段的过渡设计，真实归属应由 appId 映射或扫码站点决定（见 RiderAuthService 注释）。
 */
public record MiniLoginRequest(

        @NotBlank(message = "登录凭证 code 不能为空")
        @Size(max = 128, message = "code 过长")
        String code,

        @Size(max = 64, message = "租户编码过长")
        String tenantCode) {
}

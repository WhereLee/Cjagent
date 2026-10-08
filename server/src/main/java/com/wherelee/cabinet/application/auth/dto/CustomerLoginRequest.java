package com.wherelee.cabinet.application.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户端登录入参。
 *
 * <p>{@code tenantCode} 只在<b>首次注册</b>时必填；已有账号不传则沿用自身归属。
 * 这是过渡设计（已登记简化）：真实归属应由扫柜机码带过来的站点/运营商决定，
 * 而不是前端传值——接口形态（code + 上下文 → token）保持不变，届时只换归属解析那一环。
 */
public record CustomerLoginRequest(

        @NotBlank(message = "登录凭证 code 不能为空")
        @Size(max = 128, message = "code 过长")
        String code,

        @Size(max = 64, message = "租户编码过长")
        String tenantCode) {
}

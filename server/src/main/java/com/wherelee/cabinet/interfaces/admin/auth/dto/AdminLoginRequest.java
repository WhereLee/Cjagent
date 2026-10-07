package com.wherelee.cabinet.interfaces.admin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 后台登录入参。
 *
 * <p>登录必须带租户编码：用户名只在租户内唯一（{@code uk(tenant_id, username)}），
 * 不带租户就定位不到唯一账号。
 *
 * <p>口令字段不参与 toString（Lombok 未生成 toString，且日志里绝不打印入参原文），
 * 避免密码进审计日志。
 */
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

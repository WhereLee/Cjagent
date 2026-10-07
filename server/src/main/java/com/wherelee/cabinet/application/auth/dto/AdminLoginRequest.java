package com.wherelee.cabinet.application.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 后台登录入参。
 *
 * <p>定义在 application 而不是 Controller 包里，是因为 Service 要直接接收它；
 * 放在 interfaces 就会让下层反向依赖上层（ArchitectureTest 有一条规则专门抓这个）。
 *
 * <p>登录必须带租户编码：用户名只租户内唯一（{@code uk(tenant_id, username)}），
 * 不带租户就定位不到唯一账号。口令不落日志、不进 toString 以外的任何输出。
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

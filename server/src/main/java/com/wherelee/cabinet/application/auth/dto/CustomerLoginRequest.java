package com.wherelee.cabinet.application.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户端登录入参。
 *
 * <p>{@code tenantCode} 只在<b>首次注册</b>时必填；已有账号不传则沿用自身归属。
 * 这是过渡设计（已登记简化）：真实归属应由扫柜机码带过来的站点/运营商决定，
 * 而不是前端传值——接口形态（code + 上下文 → token）保持不变，届时只换归属解析那一环。
 *
 * <p>{@code phone} 同样只在<b>首次注册</b>时必填（定-26），但**不在注解上写 @NotBlank**：
 * 那个注解分不出“新注册”与“老账号登录”，一加就会把所有已有客户的日常登录一起拦死。
 * 所以“注册必须带号”这条写在服务端的注册分支里（{@code CustomerAuthService#registerCustomer}）。
 *
 * <p>另需知道一个局限（用户 2026-10-12 已拍板接受）：我们无渠道校验这个号是谁的，
 * 它是“客户自己填的号”，不是“运营商确认过的号”。因此定-25 的手机尾号这一因子
 * 只抬高了路人猜码的门槛，不防蓄意冒领（拿到码的人可以填自己的号）。
 */
public record CustomerLoginRequest(

        @NotBlank(message = "登录凭证 code 不能为空")
        @Size(max = 128, message = "code 过长")
        String code,

        @Size(max = 64, message = "租户编码过长")
        String tenantCode,

        @Size(max = 20, message = "手机号长度不正确")
        String phone) {
}

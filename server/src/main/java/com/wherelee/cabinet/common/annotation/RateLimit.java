package com.wherelee.cabinet.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流：Redis 固定窗口计数（Lua 保证 INCR 与 EXPIRE 原子），超限返回 42900（HTTP 429）。
 *
 * <p>维度选择很重要，默认按<b>登录用户</b>：
 * <ul>
 *   <li>{@code USER}：已登录接口的常规选择。未登录时退化到 IP，不会出现"大家共用一个配额"</li>
 *   <li>{@code IP}：登录、发短信这类匿名可达接口（但注意真实部署时取的是 X-Forwarded-For，
 *       未经代理直连时取 remoteAddr）</li>
 *   <li>{@code GLOBAL}：保护下游容量的总闸，如第三方接口调用</li>
 * </ul>
 *
 * <p>这是<b>单机近似</b>的固定窗口：多实例共享 Redis 时窗口边界仍可能有 2 倍突刺。
 * 要精确滑动窗口需换 ZSET 实现，成本更高，等真出现要求再说。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    enum Dimension {
        /** 按登录用户，未登录退化到 IP。 */
        USER,
        /** 按来源 IP。 */
        IP,
        /** 全局总量。 */
        GLOBAL
    }

    /** 限流桶名，留空则用 类名#方法名。同一业务多个接口共享配额时显式写成同一个值。 */
    String key() default "";

    /** 窗口内允许的请求数。 */
    int limit() default 60;

    /** 窗口长度，秒。 */
    int windowSeconds() default 60;

    Dimension dimension() default Dimension.USER;

    String message() default "请求过于频繁，请稍后重试";
}

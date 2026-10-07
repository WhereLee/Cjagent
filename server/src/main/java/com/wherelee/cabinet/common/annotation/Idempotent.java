package com.wherelee.cabinet.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等：同一逻辑请求在窗口内只允许执行一次，重复请求返回 40900（HTTP 409）。
 *
 * <p>{@code key} 支持 SpEL，取方法参数：{@code "#req.orderNo"}、{@code "#args[0]"}。
 * <b>不写 key 时退化为"用户 + URI + 入参摘要"</b>——这是防连点的兜底，
 * 但业务上真正的幂等（下单、开锁、退款）必须显式指定业务键，
 * 否则用户改了一个无关字段就绕过了幂等判断。
 *
 * <p>释放策略：业务<b>异常时删除占位</b>（允许重试），成功时保留到窗口结束（防重复提交）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

    /** SpEL 表达式指定的业务键；留空则用 用户+URI+入参摘要 兜底。 */
    String key() default "";

    /** 幂等窗口，秒。默认 5 分钟。 */
    long ttlSeconds() default 300L;

    String message() default "请勿重复提交";

    /**
     * 是否要求显式业务键。设为 true 时，切面在 {@code #key} 解析为空的情况下直接拒绝执行
     * ——比"静默退化成兜底键"安全：写错的注解不该变成幂等保护的漏洞。
     */
    boolean requireKey() default false;
}

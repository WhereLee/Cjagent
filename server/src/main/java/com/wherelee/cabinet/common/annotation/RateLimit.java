package com.wherelee.cabinet.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    enum Dimension {
        USER,
        IP,
        GLOBAL
    }

    String key() default "";

    int limit() default 60;

    int windowSeconds() default 60;

    Dimension dimension() default Dimension.USER;

    String message() default "请求过于频繁，请稍后重试";
}

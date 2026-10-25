package com.wherelee.cabinet.platform.idempotent;

import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.mask.MaskUtils;
import com.wherelee.cabinet.platform.security.AuthenticatedPrincipal;
import com.wherelee.cabinet.platform.security.Caller;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Aspect
@Component
@Order(-100)
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);
    private static final String KEY_PREFIX = "cab:idem:";
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final StringRedisTemplate redis;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public IdempotentAspect(StringRedisTemplate redis, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String key = buildKey(joinPoint, idempotent);
        boolean acquired;
        try {
            Boolean result = redis.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(Math.max(1, idempotent.ttlSeconds())));
            acquired = Boolean.TRUE.equals(result);
        } catch (RuntimeException e) {
            log.error("幂等存储不可用，本次拒绝执行 key={}", key, e);
            throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }

        if (!acquired) {
            log.warn("重复请求被拒 key={}", key);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, idempotent.message());
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            releaseQuietly(key);
            throw throwable;
        }
    }

    private String buildKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {
        String method = ((MethodSignature) joinPoint.getSignature()).getMethod().getName();
        String declaring = joinPoint.getTarget().getClass().getSimpleName();
        String expression = idempotent.key();

        if (StringUtils.hasText(expression)) {
            Object value = evaluate(expression, joinPoint);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return KEY_PREFIX + declaring + "#" + method + ":" + value;
            }
            if (idempotent.requireKey()) {
                throw new IllegalStateException("@Idempotent(requireKey=true) 的 key 表达式解析为空："
                        + declaring + "#" + method + " 表达式=" + expression);
            }
        }
        if (idempotent.requireKey()) {
            throw new IllegalStateException("@Idempotent(requireKey=true) 必须提供 key 表达式："
                    + declaring + "#" + method);
        }

        Caller principal = AuthenticatedPrincipal.currentOrNull();
        String actor = principal != null ? "u" + principal.subjectId() : "anon";
        String summary = MaskUtils.toMaskedSummary(objectMapper, joinPoint.getArgs());
        return KEY_PREFIX + declaring + "#" + method + ":" + actor + ":" + sha256(summary);
    }

    private Object evaluate(String expression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = NAME_DISCOVERER.getParameterNames(signature.getMethod());
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        for (int i = 0; i < args.length; i++) {
            context.setVariable("a" + i, args[i]);
            context.setVariable("p" + i, args[i]);
        }
        context.setVariable("args", args);

        Expression parsed = PARSER.parseExpression(expression);
        return parsed.getValue(context);
    }

    private void releaseQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("幂等占位释放失败，将等 TTL 过期 key={} 原因={}", key, e.getMessage());
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.valueOf(raw).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}

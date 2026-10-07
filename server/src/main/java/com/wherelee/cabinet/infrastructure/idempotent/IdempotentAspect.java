package com.wherelee.cabinet.infrastructure.idempotent;

import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.mask.MaskUtils;
import com.wherelee.cabinet.infrastructure.security.AuthenticatedPrincipal;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
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

/**
 * 幂等切面：Redis {@code SETNX} 抢占，重复请求返回 40900（HTTP 409）。
 *
 * <p>释放策略：<b>业务异常时删掉占位</b>（否则一次失败就把这个业务键永久锁死，用户重试无门）；
 * 成功时保留到窗口结束（这才是"防重复提交"的本意）。
 *
 * <p><b>Redis 不可用时 fail-closed（拒绝）</b>，与限流切面相反：幂等是正确性约束，
 * 宁可拒绝一次请求，也不能在无法判断"是不是重复"的时候放行——那会产生重复下单、重复开锁。
 *
 * <p>关于 SpEL 的安全性：表达式文本取自注解常量（编译期确定），<b>用户输入只作为变量绑定</b>，
 * 永远不拼进表达式字符串。所以这里用 StandardEvaluationContext 不构成 SpEL 注入面；
 * 反过来，如果哪天改成"表达式由配置或数据库提供"，就必须换 SimpleEvaluationContext。
 */
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
            // 业务失败：释放占位，允许重试
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
                // 注解写错（业务键为空）不能静默退化成兜底键，否则幂等保护形同不存在
                throw new IllegalStateException("@Idempotent(requireKey=true) 的 key 表达式解析为空："
                        + declaring + "#" + method + " 表达式=" + expression);
            }
        }
        if (idempotent.requireKey()) {
            throw new IllegalStateException("@Idempotent(requireKey=true) 必须提供 key 表达式："
                    + declaring + "#" + method);
        }

        // 兜底键：调用者 + 方法 + 入参摘要哈希，用于防连点
        VerifiedToken principal = AuthenticatedPrincipal.currentOrNull();
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
        // 参数名被编译选项抹掉时（-parameters 缺失），仍可用 #a0/#p0/#args 取值的兜底
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
            // 删不掉就等 TTL 自然过期；这里再抛异常会盖掉业务本来的失败原因
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

package com.wherelee.cabinet.platform.ratelimit;

import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.platform.web.WebUtils;
import com.wherelee.cabinet.platform.security.AuthenticatedPrincipal;
import com.wherelee.cabinet.platform.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(-150)
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
    private static final String KEY_PREFIX = "cab:rl:";

    private static final DefaultRedisScript<Long> COUNTER_SCRIPT = counterScript();

    private static DefaultRedisScript<Long> counterScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rate-limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final StringRedisTemplate redis;

    @org.springframework.beans.factory.annotation.Value("${cabinet.ratelimit.enabled:true}")
    private boolean enabled;

    public RateLimitAspect(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!enabled) {
            log.warn("限流已关闭（cabinet.ratelimit.enabled=false），仅用于本地压测；任何真实环境不得这么配");
            return joinPoint.proceed();
        }

        String bucket = bucketOf(joinPoint, rateLimit);
        long windowSeconds = Math.max(1, rateLimit.windowSeconds());

        long slot = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = KEY_PREFIX + bucket + ":" + slot;

        Long current;
        try {
            current = redis.execute(COUNTER_SCRIPT, java.util.List.of(key), String.valueOf(windowSeconds));
        } catch (RuntimeException e) {
            log.error("限流不可用，本次放行 bucket={}", bucket, e);
            return joinPoint.proceed();
        }

        if (current != null && current > rateLimit.limit()) {
            log.warn("触发限流 bucket={} current={} limit={}", bucket, current, rateLimit.limit());
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, rateLimit.message());
        }
        return joinPoint.proceed();
    }

    private String bucketOf(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String name = rateLimit.key().isBlank()
                ? joinPoint.getSignature().getDeclaringType().getSimpleName()
                    + "#" + joinPoint.getSignature().getName()
                : rateLimit.key();

        return switch (rateLimit.dimension()) {
            case GLOBAL -> name + ":global";
            case IP -> name + ":ip:" + currentIp();
            case USER -> {
                Caller principal = AuthenticatedPrincipal.currentOrNull();
                yield name + ":" + (principal != null
                        ? "u" + principal.subjectId()
                        : "ip" + currentIp());
            }
        };
    }

    private String currentIp() {
        HttpServletRequest request = WebUtils.currentRequest();
        String ip = WebUtils.clientIp(request);
        return ip == null ? "unknown" : ip;
    }
}

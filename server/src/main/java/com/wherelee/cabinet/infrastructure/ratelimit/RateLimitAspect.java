package com.wherelee.cabinet.infrastructure.ratelimit;

import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.web.WebUtils;
import com.wherelee.cabinet.infrastructure.security.AuthenticatedPrincipal;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
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

/**
 * 限流切面：Redis 固定窗口计数，超限抛 42900（HTTP 429）。
 *
 * <p>计数走 Lua 而不是 {@code INCR} + {@code EXPIRE} 两条命令：后者在两条命令之间崩溃或
 * 超时会给 key 留下<b>没有 TTL</b> 的状态，这个桶就永久累加、接口被永久限死。
 *
 * <p><b>Redis 不可用时故意 fail-open（放行）</b>：限流是保护措施，不是访问控制，
 * 让它故障时把整站变成 503 是更严重的可用性事故。安全上因此留了一个口子——
 * 所以<b>鉴权、幂等、额度扣减绝不能依赖本切面</b>（幂等切面相反，是 fail-closed）。
 */
@Aspect
@Component
@Order(-150)
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
    private static final String KEY_PREFIX = "cab:rl:";

    private static final DefaultRedisScript<Long> COUNTER_SCRIPT = counterScript();

    /** DefaultRedisScript 没有 (Resource, Class) 构造器，只能 setter 装配。 */
    private static DefaultRedisScript<Long> counterScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rate-limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final StringRedisTemplate redis;

    /**
     * 仅供本地压测关掉限流用。
     *
     * <p>关掉后才能量到“分配路径本身”的吞吐——否则 100 并发压一个 20 次/分的桶，
     * 测到的是限流器而不是并发控制。代价是多一个开关：所以关掉时必定打一条 warn，
     * 并且**要把这一条写进压测报告的口径里**（不标注的数字不可信）。
     */
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

        // 窗口编号进 key：桶随时间自然轮转，不依赖 EXPIRE 的精度
        long slot = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = KEY_PREFIX + bucket + ":" + slot;

        Long current;
        try {
            current = redis.execute(COUNTER_SCRIPT, java.util.List.of(key), String.valueOf(windowSeconds));
        } catch (RuntimeException e) {
            // fail-open：Redis 故障时放行并告警，不阻断业务
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
                VerifiedToken principal = AuthenticatedPrincipal.currentOrNull();
                // 未登录时退化到 IP，避免所有匿名请求共用一个桶（那等于给攻击者免费放大限流）
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

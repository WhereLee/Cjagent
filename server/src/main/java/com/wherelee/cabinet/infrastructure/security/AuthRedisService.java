package com.wherelee.cabinet.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 凭证吊销与权限缓存（Redis db8，键前缀 {@code cab:auth:}）。
 *
 * <p><b>为什么 JWT 还要配 Redis</b>：JWT 自包含、无法主动作废，注销/改密/冻结账号后
 * 旧 token 仍会有效到过期。用"jti 黑名单 + 过期即清理 TTL"补上作废能力，是最省事的常见做法。
 *
 * <p><b>fail closed</b>：黑名单读不到时按"已作废"处理（返回 401），不返回"那就放行"。
 * 认证组件出故障时宁可拒绝请求，也不能在无法确认作废状态时放行。
 */
@Service
public class AuthRedisService {

    private static final Logger log = LoggerFactory.getLogger(AuthRedisService.class);
    private static final String BLACKLISTED = "1";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AuthRedisService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 注销：把 jti 拉黑，TTL 设成凭证剩余有效期，过期后自然清理不留痕。 */
    public void blacklist(String jti, Duration remainingTtl) {
        if (remainingTtl == null || remainingTtl.isZero()) {
            return; // 已过期，无需拉黑
        }
        redis.opsForValue().set(AuthConstants.KEY_BLACKLIST + jti, BLACKLISTED, remainingTtl);
    }

    public boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(AuthConstants.KEY_BLACKLIST + jti));
        } catch (RuntimeException e) {
            log.error("读取黑名单失败，按已作废处理（fail closed），jti={}", jti, e);
            throw new BizException(ResultCode.UNAUTHORIZED, "登录状态校验不可用，请稍后重试");
        }
    }

    /**
     * 记住当前有效的 refresh jti：<b>同一账号只保留最后一次签发的 refresh</b>（单设备语义）。
     *
     * <p>这是有意的取舍，已登记在简化项清单：企业标准应支持多设备并发在线，
     * 那时这里要改成集合（一个账号多个 refresh jti），刷新时按 jti 精确摘除。
     * 现在做成单设备的收益是：refresh 天然轮换，旧 refresh 一换新就失效，泄露面小。
     */
    public void rememberRefreshToken(String end, Long subjectId, String jti, Duration ttl) {
        redis.opsForValue().set(refreshKey(end, subjectId), jti, ttl);
    }

    public boolean isCurrentRefreshToken(String end, Long subjectId, String jti) {
        try {
            String current = redis.opsForValue().get(refreshKey(end, subjectId));
            return jti.equals(current);
        } catch (RuntimeException e) {
            log.error("读取有效 refresh 失败，按不匹配处理（fail closed），end={} subjectId={}", end, subjectId, e);
            throw new BizException(ResultCode.UNAUTHORIZED, "登录状态校验不可用，请稍后重试");
        }
    }

    public void dropRefreshToken(String end, Long subjectId) {
        redis.delete(refreshKey(end, subjectId));
    }

    private String refreshKey(String end, Long subjectId) {
        return AuthConstants.KEY_REFRESH + end + ":" + subjectId;
    }

    /** 权限清单缓存（ROLE_x 与 system:xxx 拼在前缀里，存同一个列表）：命中就不查库。 */
    public List<String> cachedAuthorities(Long userId) {
        try {
            String json = redis.opsForValue().get(AuthConstants.KEY_PERMISSION_CACHE + userId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (RuntimeException | JsonProcessingException e) {
            // 缓存坏不影响正确性：返回 null 让调用方回源查库
            log.warn("权限缓存读取失败，回源查库 userId={}", userId, e);
            return null;
        }
    }

    public void cacheAuthorities(Long userId, List<String> authorities, Duration ttl) {
        try {
            redis.opsForValue().set(AuthConstants.KEY_PERMISSION_CACHE + userId,
                    objectMapper.writeValueAsString(authorities), ttl);
        } catch (JsonProcessingException e) {
            log.warn("权限缓存写入失败，忽略（下次请求会回源）userId={}", userId, e);
        }
    }

    /** 角色/权限变更后必须调用，否则最长要等一个 TTL 才生效。 */
    public void evictAuthorities(Long userId) {
        redis.delete(AuthConstants.KEY_PERMISSION_CACHE + userId);
    }
}

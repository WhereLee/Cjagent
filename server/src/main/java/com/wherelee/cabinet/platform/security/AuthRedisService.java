package com.wherelee.cabinet.platform.security;

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

    public void blacklist(String jti, Duration remainingTtl) {
        if (remainingTtl == null || remainingTtl.isZero()) {
            return;
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

    public List<String> cachedAuthorities(Long userId) {
        try {
            String json = redis.opsForValue().get(AuthConstants.KEY_PERMISSION_CACHE + userId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (RuntimeException | JsonProcessingException e) {
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

    public void evictAuthorities(Long userId) {
        redis.delete(AuthConstants.KEY_PERMISSION_CACHE + userId);
    }
}

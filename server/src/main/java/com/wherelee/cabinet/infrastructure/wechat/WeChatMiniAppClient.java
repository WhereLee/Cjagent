package com.wherelee.cabinet.infrastructure.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.config.CabinetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 真实实现：调用微信 {@code jscode2session} 换 openid。
 *
 * <p>只在 {@code cabinet.mini.mock-login=false}（默认）时装配。
 *
 * <p>注意：这个接口的返回值里包含 session_key，<b>绝对不要记录、不要下发给前端</b> ——
 * 它能用来解密用户的手机号等敏感数据。这里只取 openid 与 unionid。
 */
@Component
@ConditionalOnProperty(prefix = "cabinet.mini", name = "mock-login", havingValue = "false", matchIfMissing = true)
public class WeChatMiniAppClient implements MiniAppClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatMiniAppClient.class);

    private final RestClient restClient;
    private final CabinetProperties.Mini config;

    public WeChatMiniAppClient(CabinetProperties properties, RestClient.Builder builder) {
        this.config = properties.getMini();
        this.restClient = builder.build();
    }

    @Override
    public String resolveOpenId(String code) {
        if (!StringUtils.hasText(config.getAppId()) || !StringUtils.hasText(config.getAppSecret())) {
            // 配置缺失属于部署问题，不是用户错误：说清楚，避免运维对着 401 干瞪眼
            throw new IllegalStateException(
                    "cabinet.mini.app-id / app-secret 未配置（server/.env 里的 WECHAT_APP_ID、WECHAT_APP_SECRET）");
        }

        JsonNode body = restClient.get()
                .uri(config.getCode2SessionUrl(), builder -> builder
                        .queryParam("appid", config.getAppId())
                        .queryParam("secret", config.getAppSecret())
                        .queryParam("js_code", code)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (body == null) {
            log.error("jscode2session 返回空响应");
            throw new BizException(ResultCode.UNAUTHORIZED, "微信登录失败，请重试");
        }

        int errCode = body.path("errcode").asInt(0);
        if (errCode != 0) {
            // 微信的 errmsg 会带 openid/appid 线索，只进日志不进响应
            log.warn("jscode2session 失败 errcode={} errmsg={}", errCode, body.path("errmsg").asText());
            throw new BizException(ResultCode.UNAUTHORIZED, "登录凭证无效，请重新登录");
        }

        String openId = body.path("openid").asText(null);
        if (!StringUtils.hasText(openId)) {
            log.warn("jscode2session 未返回 openid");
            throw new BizException(ResultCode.UNAUTHORIZED, "登录凭证无效，请重新登录");
        }
        return openId;
    }
}

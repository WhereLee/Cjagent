package com.wherelee.cabinet.infrastructure.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.app.CabinetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

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

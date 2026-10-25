package com.wherelee.cabinet.infrastructure.wechat;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "cabinet.mini", name = "mock-login", havingValue = "true")
public class MockMiniAppClient implements MiniAppClient {

    private static final Logger log = LoggerFactory.getLogger(MockMiniAppClient.class);
    private static final String MOCK_OPENID_PREFIX = "mock-openid-";

    @Override
    public String resolveOpenId(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ResultCode.UNAUTHORIZED, "登录凭证无效，请重新登录");
        }
        log.warn("小程序登录走的是 MOCK（cabinet.mini.mock-login=true），生产环境必须关闭：code 长度={}", code.length());
        return MOCK_OPENID_PREFIX + code;
    }
}

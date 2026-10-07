package com.wherelee.cabinet.infrastructure.wechat;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 本地/测试用的假微信：<b>不做验签</b>，把 code 直接当成 openid 的来源。
 *
 * <p>存在的理由：没有小程序 AppID 时也想把"登录 → 拿 token → 带 token 调接口"这条链跑通并写进测试。
 *
 * <p>装配条件是双重保险，任一不满足就不会生效：
 * <ul>
 *   <li>{@code cabinet.mini.mock-login=true}（默认 false）</li>
 *   <li>激活 profile 不是 prod（{@code @Profile("!prod")}）</li>
 * </ul>
 *
 * <p><b>安全边界必须说清</b>：mock 打开时，任何人传任意 code 都能拿到一个 openid，
 * 也就等于能登录成任意已存在的账号（首次登录还要自己指定租户编码）。
 * 因此它只能是 dev 的便利，<b>绝不能带进生产</b>；生产要换回 {@link WeChatMiniAppClient}。
 * 每次调用都会打 WARN，方便在日志里发现"这东西居然在线上开着"。
 */
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

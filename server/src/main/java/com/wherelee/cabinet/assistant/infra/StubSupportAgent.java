package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 桩实现（默认）：AI 客服未开启时的固定话术。
 *
 * <p>与复审的桩同一哲学：CI 与集成测试<b>永不调用付费接口</b>——结论不可重现、会限流、
 * 还要管密钥。接真模型只改 {@code cabinet.agent.client} 一项 + 环境变量。
 */
@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "stub", matchIfMissing = true)
public class StubSupportAgent implements SupportAgentPort {

    @Override
    public AgentReply chat(SupportSession session) {
        return AgentReply.stub();
    }
}

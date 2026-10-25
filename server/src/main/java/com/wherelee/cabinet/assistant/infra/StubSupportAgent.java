package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "stub", matchIfMissing = true)
public class StubSupportAgent implements SupportAgentPort {

    @Override
    public AgentReply chat(SupportSession session) {
        return AgentReply.stub();
    }
}

package com.wherelee.cabinet.locker.infra;

import com.wherelee.cabinet.domain.entity.BizOutbox;
import com.wherelee.cabinet.locker.api.OutboxPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "mq", matchIfMissing = true)
public class RocketMqOutboxPublisher implements OutboxPublisher {

    public static final String HEADER_TENANT_ID = "tenantId";

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public RocketMqOutboxPublisher(RocketMQTemplate rocketMQTemplate,
                                   @Value("${cabinet.outbox.topic:cabinet-outbox}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(BizOutbox row) {
        Message<String> message = MessageBuilder.withPayload(row.getPayload())
                .setHeader(HEADER_TENANT_ID, String.valueOf(row.getTenantId()))
                .setHeader(RocketMQHeaders.KEYS, row.getDedupKey())
                .build();
        rocketMQTemplate.syncSend(topic + ":" + row.getEventType().name(), message);
    }
}

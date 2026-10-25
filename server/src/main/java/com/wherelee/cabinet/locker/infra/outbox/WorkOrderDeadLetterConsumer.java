package com.wherelee.cabinet.locker.infra.outbox;

import com.wherelee.cabinet.locker.api.DeadLetterPort;
import com.wherelee.cabinet.locker.infra.RocketMqOutboxPublisher;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "mq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "%DLQ%${cabinet.outbox.group.workorder:cabinet-outbox-workorder}",
        consumerGroup = "${cabinet.outbox.dlq-group.workorder:cabinet-outbox-dlq-workorder}")
public class WorkOrderDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderDeadLetterConsumer.class);

    private static final String ORIGIN_TAG = "WORK_ORDER_ANOMALY";

    private final DeadLetterPort deadLetters;
    private final String originTopic;

    public WorkOrderDeadLetterConsumer(DeadLetterPort deadLetters,
                                       @Value("${cabinet.outbox.topic:cabinet-outbox}") String originTopic) {
        this.deadLetters = deadLetters;
        this.originTopic = originTopic;
    }

    @Override
    public void onMessage(MessageExt message) {
        log.error("工单事件进入死信 msgId={} key={} times={}",
                message.getMsgId(), message.getProperty(RocketMQHeaders.KEYS), message.getReconsumeTimes());
        deadLetters.archive(originTopic, ORIGIN_TAG, message.getTopic(),
                tenantOf(message), message.getProperty(RocketMQHeaders.KEYS),
                new String(message.getBody(), StandardCharsets.UTF_8), null, message.getReconsumeTimes());
    }

    private static Long tenantOf(MessageExt message) {
        String raw = message.getUserProperty(RocketMqOutboxPublisher.HEADER_TENANT_ID);
        try {
            return raw == null ? null : Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

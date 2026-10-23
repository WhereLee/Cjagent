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

/**
 * 死信消费者：{@code OWNER_NOTICE} 重试 16 次仍失败的消息，归档进 {@code biz_dead_letter}。
 *
 * <p>与 {@link WorkOrderDeadLetterConsumer} 同构，只有 tag、原组、本组三处不同。
 * 分成两个类而不是一个类订阅两个 DLQ：{@code @RocketMQMessageListener} 一个注解只能挂一个
 * topic，而且两个业务的死信本来就该各自独立——一个卡住不该拖另一个。
 */
@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "mq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "%DLQ%${cabinet.outbox.group.notice:cabinet-outbox-notice}",
        consumerGroup = "${cabinet.outbox.dlq-group.notice:cabinet-outbox-dlq-notice}")
public class OwnerNoticeDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(OwnerNoticeDeadLetterConsumer.class);

    private static final String ORIGIN_TAG = "OWNER_NOTICE";

    private final DeadLetterPort deadLetters;
    private final String originTopic;

    public OwnerNoticeDeadLetterConsumer(DeadLetterPort deadLetters,
                                         @Value("${cabinet.outbox.topic:cabinet-outbox}") String originTopic) {
        this.deadLetters = deadLetters;
        this.originTopic = originTopic;
    }

    @Override
    public void onMessage(MessageExt message) {
        log.error("物主通知进入死信 msgId={} key={} times={}",
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

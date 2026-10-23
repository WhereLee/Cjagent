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
 * 死信消费者：{@code WORK_ORDER_ANOMALY} 重试 16 次仍失败的消息，归档进 {@code biz_dead_letter}。
 *
 * <p><b>必须是独立的 consumerGroup</b>：RocketMQ 的死信消息不会被原消费组再次消费，
 * 换一个组订阅 {@code %DLQ%{原组}} 才是标准做法。用原组订阅，一条也收不到。
 *
 * <p>这里只归档、不重试。归档之后消息就算"被吃掉了"——证据在数据库里，不在 MQ 里。
 * 处理靠 {@code DeadLetterPort.replay}：先看清原因，再决定投回去。
 */
@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "mq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "%DLQ%${cabinet.outbox.group.workorder:cabinet-outbox-workorder}",
        consumerGroup = "${cabinet.outbox.dlq-group.workorder:cabinet-outbox-dlq-workorder}")
public class WorkOrderDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderDeadLetterConsumer.class);

    /** 重放目标：这条消息本来该去哪。 */
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

    /** 坏消息可能连租户都没有；为 null 时那条将不可重放，只能人工看。 */
    private static Long tenantOf(MessageExt message) {
        String raw = message.getUserProperty(RocketMqOutboxPublisher.HEADER_TENANT_ID);
        try {
            return raw == null ? null : Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

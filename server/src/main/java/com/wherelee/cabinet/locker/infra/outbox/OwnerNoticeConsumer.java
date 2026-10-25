package com.wherelee.cabinet.locker.infra.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.OutboxPort.OwnerNoticePayload;
import com.wherelee.cabinet.locker.infra.RocketMqOutboxPublisher;
import com.wherelee.cabinet.notify.api.NoticePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "mq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${cabinet.outbox.topic:cabinet-outbox}",
        selectorExpression = "OWNER_NOTICE",
        consumerGroup = "${cabinet.outbox.group.notice:cabinet-outbox-notice}")
public class OwnerNoticeConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(OwnerNoticeConsumer.class);

    private final NoticePort notices;
    private final ObjectMapper json;
    private final Counter dropped;

    public OwnerNoticeConsumer(NoticePort notices, ObjectMapper json, MeterRegistry meterRegistry) {
        this.notices = notices;
        this.json = json;
        this.dropped = Counter.builder("cabinet.outbox.consumer.dropped")
                .tag("type", "OWNER_NOTICE")
                .description("消费失败但重试无意义、已 ACK 丢弃的事件数")
                .register(meterRegistry);
    }

    @Override
    public void onMessage(MessageExt message) {
        long tenantId = Long.parseLong(message.getUserProperty(RocketMqOutboxPublisher.HEADER_TENANT_ID));
        OwnerNoticePayload payload = read(message);

        try {
            TenantContext.runAs(tenantId, () ->
                    notices.emit(tenantId, payload.channel(), payload.kind(), payload.dedupKey(),
                            payload.content(), payload.customerId(), payload.workOrderId(), payload.orderNo()));
        } catch (BizException e) {
            dropped.increment();
            log.error("物主通知事件无法投递（重试无意义）msgId={} key={} kind={}：{}",
                    message.getMsgId(), message.getKeys(), payload.kind(), e.getMessage(), e);
        }
    }

    private OwnerNoticePayload read(MessageExt message) {
        try {
            return json.readValue(message.getBody(), OwnerNoticePayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("outbox 载荷无法解析 msgId=" + message.getMsgId(), e);
        }
    }
}

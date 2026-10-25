package com.wherelee.cabinet.locker.infra.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.OutboxPort.WorkOrderAnomalyPayload;
import com.wherelee.cabinet.locker.infra.RocketMqOutboxPublisher;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
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
        selectorExpression = "WORK_ORDER_ANOMALY",
        consumerGroup = "${cabinet.outbox.group.workorder:cabinet-outbox-workorder}")
public class WorkOrderAnomalyConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderAnomalyConsumer.class);

    private final WorkOrderPort workOrders;
    private final ObjectMapper json;
    private final Counter inconsistent;

    public WorkOrderAnomalyConsumer(WorkOrderPort workOrders, ObjectMapper json,
                                    MeterRegistry meterRegistry) {
        this.workOrders = workOrders;
        this.json = json;
        this.inconsistent = Counter.builder("cabinet.outbox.consumer.dropped")
                .tag("type", "WORK_ORDER_ANOMALY")
                .description("消费失败但重试无意义、已 ACK 丢弃的事件数")
                .register(meterRegistry);
    }

    @Override
    public void onMessage(MessageExt message) {
        long tenantId = Long.parseLong(message.getUserProperty(RocketMqOutboxPublisher.HEADER_TENANT_ID));
        WorkOrderAnomalyPayload payload = read(message);

        try {
            TenantContext.runAs(tenantId, () ->
                    workOrders.openForAnomaly(payload.slotId(), payload.anomaly(), payload.reason()));
        } catch (BizException e) {
            inconsistent.increment();
            log.error("格口异常事件无法开单（现场不一致，重试无意义）msgId={} key={} slotId={}：{}",
                    message.getMsgId(), message.getKeys(), payload.slotId(), e.getMessage(), e);
        }
    }

    private WorkOrderAnomalyPayload read(MessageExt message) {
        try {
            return json.readValue(message.getBody(), WorkOrderAnomalyPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("outbox 载荷无法解析 msgId=" + message.getMsgId(), e);
        }
    }
}

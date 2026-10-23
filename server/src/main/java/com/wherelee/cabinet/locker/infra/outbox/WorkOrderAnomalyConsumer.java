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

/**
 * 消费 {@code WORK_ORDER_ANOMALY}：格口异常落定后开工单。
 *
 * <p>这是接上 broker 之后<strong>生产者不再认识的那个消费者</strong>——中继只管把事实投出去，
 * 开单逻辑搬到这里。以后要加"异常同时推企业微信"，再写一个 listener 即可，中继一行不改。
 *
 * <p>三件事缺一不可：
 * <ol>
 *   <li><b>从 header 重建租户</b>：消费线程没有请求上下文，不做就是越租写库</li>
 *   <li><b>{@link BizException} 要吞掉并 ACK</b>：它代表"撞了唯一索引却查不到未关闭单"
 *       这种现场不一致，重试 16 次也不会变好，只会把坏消息推进死信队列</li>
 *   <li><b>其余异常一律抛</b>：网络抖动、库瞬时不可用这类才该走 MQ 的重试</li>
 * </ol>
 *
 * <p>普通的重复投递<strong>不会</strong>抛异常：{@code WorkOrderIntakeService} 自己 catch 了
 * {@code DuplicateKeyException} 并回读已有单返回。所以消费侧不需要额外的 Inbox 表。
 */
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
        // 这个计数器的含义是"消费失败但重试无意义、已选择丢弃"。
        // 它一旦非零，说明有格口异常没开成工单，必须有人去看。
        this.inconsistent = Counter.builder("cabinet.outbox.consumer.dropped")
                .tag("type", "WORK_ORDER_ANOMALY")
                .description("消费失败但重试无意义、已 ACK 丢弃的事件数")
                .register(meterRegistry);
    }

    @Override
    public void onMessage(MessageExt message) {
        long tenantId = Long.parseLong(message.getUserProperty(RocketMqOutboxPublisher.HEADER_TENANT_ID));
        // 载荷解析失败要往上抛：坏消息应该走重试直到进死信，而不是悄悄丢掉
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

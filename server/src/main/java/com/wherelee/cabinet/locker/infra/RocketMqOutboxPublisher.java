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

/**
 * 把待投递的事实投到 RocketMQ。
 *
 * <p>投递成功后中继才把该行标 {@code DONE}，所以这里用 {@code syncSend}：中继本来就是后台任务，
 * "等 broker 确认收下"比"发出去了但不知道成没成"值——后者会让一条事实静默消失。
 *
 * <p>两条 header 是必需的：
 * <ul>
 *   <li>{@code tenantId}：消费线程没有请求上下文，不从消息里带就等于丢租户</li>
 *   <li>{@link RocketMQHeaders#KEYS}：放 {@code dedupKey}，控制台与死信排查都能按它捞</li>
 * </ul>
 *
 * <p>destination 写成 {@code topic:tag}，tag 用事件类型——**一个 topic 按类型分订阅**。
 * 这样新增一个订阅方（比如"异常同时推企业微信"）只需要多一个 listener，
 * 中继和生产者一行都不用改。
 */
@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "mq", matchIfMissing = true)
public class RocketMqOutboxPublisher implements OutboxPublisher {

    /** 租户放在这条 header 上；消费侧必须用它重建上下文。 */
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
        // 抛 MessagingException（RuntimeException）→ 交给中继的 failSoft 退避重试
        rocketMQTemplate.syncSend(topic + ":" + row.getEventType().name(), message);
    }
}

package com.wherelee.cabinet.interfaces.mq;

import com.wherelee.cabinet.application.storage.AsyncStorageOrderAppService;
import com.wherelee.cabinet.application.storage.message.OrderPersistMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "订单待落库"消费者。
 *
 * <p><b>异常必须上抛而不是吞掉</b>：rocketmq-spring 的容器把异常当作 RECONSUME_LATER，
 * 吞掉等于告诉 broker "处理成功"，消息就此丢失，用户手里的占位码变成幽灵。
 * 重试次数用尽后进死信队列 {@code %DLQ%<consumerGroup>}，由第 17 刀的告警接住。
 *
 * <p>{@code selectorExpression = "order"}：同一 topic 后续还要挂别的语义（超时释放、结算），
 * 用 tag 分流比拆多个 topic 便宜，也不会让消费者收到不属于自己的消息。
 */
@Component
@RocketMQMessageListener(
        topic = "${cabinet.mq.order-persist-topic:storage-order-persist}",
        consumerGroup = "cabinet-storage-order-persist-consumer",
        selectorExpression = "order")
public class OrderPersistListener implements RocketMQListener<OrderPersistMessage> {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistListener.class);

    private final AsyncStorageOrderAppService storageOrderAppService;

    public OrderPersistListener(AsyncStorageOrderAppService storageOrderAppService) {
        this.storageOrderAppService = storageOrderAppService;
    }

    @Override
    public void onMessage(OrderPersistMessage message) {
        if (message == null || message.orderNo() == null) {
            // 空消息/缺主键：重试也不会有变化，直接确认掉并留下痕量，避免无限重投刷爆日志
            log.error("落库消息缺少订单号，丢弃不重投 message={}", message);
            return;
        }
        log.info("收到落库消息 orderNo={} slotId={}", message.orderNo(), message.slotId());
        storageOrderAppService.persistFromMessage(message);
    }
}

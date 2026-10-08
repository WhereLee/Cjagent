package com.wherelee.cabinet.infrastructure.mq;

import com.wherelee.cabinet.application.storage.OrderPersistPublisher;
import com.wherelee.cabinet.application.storage.message.OrderPersistMessage;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * RocketMQ 实现的落库消息发送。
 *
 * <p>用 {@code KEYS} 头带订单号：broker 侧按 key 查消息是排障的第一入口
 * （"这条订单的消息到底发出去没有"），没有 key 就只能靠时间点捞。
 *
 * <p>非 OK 一律抛 {@code MIDDLEWARE_UNAVAILABLE}（5xxxx → HTTP 503）：
 * 让调用方在同一个请求里回滚预扣，而不是先给用户一个成功再默默丢单。
 */
@Service
public class RocketMqOrderPersistPublisher implements OrderPersistPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqOrderPersistPublisher.class);

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${cabinet.mq.order-persist-topic:storage-order-persist}")
    private String topic;

    @Value("${cabinet.mq.send-timeout-ms:3000}")
    private long sendTimeoutMs;

    public RocketMqOrderPersistPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void publish(OrderPersistMessage message) {
        SendResult result;
        try {
            result = rocketMQTemplate.syncSend(topic + ":order",
                    MessageBuilder.withPayload(message)
                            .setHeader(RocketMQHeaders.KEYS, message.msgKey())
                            .build(),
                    sendTimeoutMs);
        } catch (RuntimeException e) {
            log.error("落库消息发送异常 orderNo={} topic={}", message.msgKey(), topic, e);
            throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }

        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            log.error("落库消息未确认 orderNo={} status={}", message.msgKey(),
                    result == null ? "null" : result.getSendStatus());
            throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }

        log.info("落库消息已投递 orderNo={} msgId={} slotId={}",
                message.msgKey(), result.getMsgId(), message.slotId());
    }
}

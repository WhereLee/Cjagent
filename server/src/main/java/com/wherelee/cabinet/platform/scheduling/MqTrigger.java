package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.platform.scheduling.DelayReminder;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "cabinet.scheduler.trigger", havingValue = "mq")
@RocketMQMessageListener(topic = "${cabinet.scheduler.wake-topic:storage-task-wake}",
        consumerGroup = "cabinet-task-wake-consumer")
public class MqTrigger implements DelayReminder, RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(MqTrigger.class);

    private final DelayTaskService tasks;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${cabinet.scheduler.wake-topic:storage-task-wake}")
    private String wakeTopic;

    public MqTrigger(DelayTaskService tasks, RocketMQTemplate rocketMQTemplate) {
        this.tasks = tasks;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    private static final long[] DELAY_LEVEL_SECONDS = {
            1L, 5L, 10L, 30L, 60L, 120L, 180L, 240L, 300L, 360L, 420L, 480L, 540L, 600L,
            1200L, 1800L, 3600L, 7200L};

    @Override
    public void remind(TaskType type, String bizKey, LocalDateTime fireAt) {
        long delayMs = Math.max(0L, Duration.between(LocalDateTime.now(), fireAt).toMillis());
        try {
            rocketMQTemplate.syncSend(wakeTopic, MessageBuilder.withPayload(type.name() + ":" + bizKey).build(),
                    3000L, delayLevelAtLeast(delayMs));
        } catch (RuntimeException e) {
            log.warn("定时提醒发送失败，回退到轮询触发 type={} bizKey={}", type, bizKey, e);
        }
    }

    static int delayLevelAtLeast(long delayMs) {
        long need = (delayMs + 999L) / 1000L;
        for (int i = 0; i < DELAY_LEVEL_SECONDS.length; i++) {
            if (DELAY_LEVEL_SECONDS[i] >= need) {
                return i + 1;
            }
        }
        return DELAY_LEVEL_SECONDS.length;
    }

    @Override
    public void onMessage(String payload) {
        log.debug("收到任务唤醒消息 {}，执行权仍由 DB 租约判定", payload);
        try {
            tasks.runDue();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @Scheduled(fixedDelayString = "${cabinet.scheduler.poll-interval-ms:60000}",
            initialDelayString = "${cabinet.scheduler.poll-interval-ms:60000}")
    public void fallbackPoll() {
        try {
            tasks.runDue();
        } catch (RuntimeException e) {
            log.error("MQ 兜底轮询异常，下一轮继续", e);
        }
    }

    @Override
    public String mode() {
        return "mq";
    }
}

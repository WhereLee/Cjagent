package com.wherelee.cabinet.infrastructure.task;

import com.wherelee.cabinet.application.task.DelayReminder;
import com.wherelee.cabinet.application.task.DelayTaskService;
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

/**
 * 触发方式三：RocketMQ 定时/延迟消息唤醒。
 *
 * <p>优势是<b>不占用扫描、天然支持长延迟</b>，也最贴近"超时未取"这类业务分钟级到小时级的精度需求。
 *
 * <p>为什么收到消息还要去 DB 抢：延迟消息<b>至少一次投递</b>，重复唤醒和并发唤醒都会发生；
 * 若把"收到消息"当成"可以执行"，同一订单就会被释放两次。所以消息只当闹钟用。
 *
 * <p>短板也写清：rocketmq-spring 2.3.1 的 {@code syncSend(dest,msg,timeout,delayLevel)} 只支持
 * <b>18 个固定延迟档位</b>，不是任意时刻（任意定时需 5.x 的 TIMER_DELIVER_MS 头）。
 * 因此选档位时<b>向上取整</b>：对"超时释放"这类任务，提前触发会误释放还在用的格口，
 * 晚几秒只是用户多等——两个方向的不对称决定了必须往安全的一侧取。broker 不可用时同样有慢轮询兜底。
 */
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

    /** 经典延迟档位（秒）：1s 5s 10s 30s 1m 2m … 2h，共 18 级。 */
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

    /** 选"不早于目标延迟"的最小档位（向上取整）；超出最大档位则靠顶格 + 轮询兜底。 */
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
            // 上抛让 broker 重投：这次没跑成不代表任务丢了（DB 里还在），但重投能更快补上
            throw e;
        }
    }

    /** 保底慢轮询：broker 抖动或消息丢失时仍能推进。 */
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

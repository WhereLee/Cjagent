package com.wherelee.cabinet.infrastructure.task;

import com.wherelee.cabinet.application.task.DelayReminder;
import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 触发方式一：纯 DB 轮询。
 *
 * <p>最简单也最保底：不依赖 Redis 与 MQ，任务表就是队列。代价是延迟下限等于轮询间隔，
 * 且实例多时每个实例都在扫同一张表（靠 {@code SKIP LOCKED} 让候选集不相交，
 * 否则热点表会把所有实例串成一条队）。
 *
 * <p>用 {@code poll-enabled} 而不是复用 {@code trigger} 作开关：一个类上叠不了两个
 * {@code @ConditionalOnProperty}，而“选哪种加速器”与“要不要轮询”确实是两个维度
 * （zset/mq 模式下也需要一条慢轮询兜底）。
 * 集成测试把轮询关掉、自己显式调 {@code runDue()}，避免后台线程与断言并发造成“偶尔红”。
 */
@Component
@ConditionalOnProperty(name = "cabinet.scheduler.poll-enabled", havingValue = "true", matchIfMissing = true)
public class PollTrigger implements DelayReminder {

    private static final Logger log = LoggerFactory.getLogger(PollTrigger.class);

    private final DelayTaskService tasks;

    public PollTrigger(DelayTaskService tasks) {
        this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${cabinet.scheduler.poll-interval-ms:15000}",
            initialDelayString = "${cabinet.scheduler.initial-delay-ms:20000}")
    public void poll() {
        try {
            int executed = tasks.runDue();
            if (executed > 0) {
                log.info("轮询触发执行了 {} 个延迟任务", executed);
            }
        } catch (RuntimeException e) {
            // 调度线程绝不能因为一次异常就停摆：Spring 的 fixedDelay 任务抛异常不会杀死后续执行，
            // 但这里显式吞掉并记录，是为了让告警落在"任务失败"而不是"调度器静默死亡"
            log.error("延迟任务轮询异常，下一轮继续", e);
        }
    }

    @Override
    public void remind(TaskType type, String bizKey, LocalDateTime fireAt) {
        // 轮询模式下提醒就是空操作：登记已经写进任务表，下一轮自然会看到
    }

    @Override
    public String mode() {
        return "poll";
    }
}

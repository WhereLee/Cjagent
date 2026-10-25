package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.platform.scheduling.DelayReminder;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
            log.error("延迟任务轮询异常，下一轮继续", e);
        }
    }

    @Override
    public void remind(TaskType type, String bizKey, LocalDateTime fireAt) {
    }

    @Override
    public String mode() {
        return "poll";
    }
}

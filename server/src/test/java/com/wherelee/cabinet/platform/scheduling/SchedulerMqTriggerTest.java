package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.trigger=mq",
        "cabinet.scheduler.poll-interval-ms=3600000",
        "cabinet.scheduler.batch-size=50"
})
class SchedulerMqTriggerTest {

    private static final Long TENANT = 8117L;

    @Autowired
    private DelayTaskService tasks;
    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanup() {
        new JdbcTemplate(dataSource).update("delete from biz_delay_task where tenant_id = ?", TENANT);
        TenantContext.clear();
    }

    @Test
    @DisplayName("延迟消息唤醒后任务被执行，且 attempt=1（重复唤醒不会多跑）")
    void delayedWakeDrivesExecutionExactlyOnce() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LocalDateTime fireAt = LocalDateTime.now().plusSeconds(5);
        String bizKey = "MQ-" + UUID.randomUUID();
        long scheduledAt = System.currentTimeMillis();

        TenantContext.runAs(TENANT, () -> tasks.schedule(TaskType.SLOT_RELEASE, bizKey, TENANT, fireAt));

        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            Integer done = jdbc.queryForObject(
                    "select count(*) from biz_delay_task where biz_key = ? and status = 'DONE'",
                    Integer.class, bizKey);
            if (done != null && done == 1) {
                break;
            }
            sleep();
        }

        Long attempt = jdbc.queryForObject(
                "select attempt from biz_delay_task where biz_key = ?", Long.class, bizKey);
        assertEquals(Long.valueOf(1L), attempt, "任务应恰好被抢一次（attempt 是硬证据，不是日志感觉）");
        LocalDateTime executedAt = jdbc.queryForObject(
                "select update_time from biz_delay_task where biz_key = ?", LocalDateTime.class, bizKey);
        long delayMs = Duration.between(fireAt, executedAt).toMillis();
        System.out.printf("MEASURED trigger=mq targetMs=5000 observedLatencyMs=%d attempt=%d%n",
                delayMs, attempt);
        assertTrue(delayMs >= 0, "不能早于应执行时间触发（提前触发对超时释放是误伤）");
        assertTrue(delayMs < 20_000L,
                "MQ 唤醒的实测延迟 = " + delayMs + "ms（含 broker 投递与消费线程排队）");
        assertTrue(System.currentTimeMillis() - scheduledAt > 4_000L,
                "确实等到了档位时刻，而不是登记完立刻被跑（那说明延迟消息没起作用）");
    }

    private void sleep() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

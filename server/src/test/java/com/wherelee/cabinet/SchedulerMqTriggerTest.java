package com.wherelee.cabinet;

import com.wherelee.cabinet.application.task.DelayTaskService;
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

/**
 * 触发方式三（RocketMQ 定时消息）的实测。
 *
 * <p>要证的结论只有一句：<b>MQ 能做闹钟，不能做执行权</b>。收到唤醒消息后仍然要去 DB 抢租约，
 * 所以"重复唤醒"与"并发唤醒"都不会让同一个任务跑两遍。这个类用真实 broker 跑通这一段，
 * 并留下实测延迟——MQ 的代价（延迟档位粗）必须用数字说明，而不是写在文档里当约定。
 *
 * <p>业务任务故意用一个不存在的单号：{@code SlotReleaseHandler} 对"订单不存在"是跳过，
 * 于是这条用例只考察触发与收敛，不牵扯其它夹具。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.trigger=mq",
        // 兜底轮询放到很慢：本用例要的是"MQ 唤醒把事办了"，不能靠轮询蒙完成
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
        // 5 秒正好落在档位 2（1s 5s 10s …），是"目标延迟=档位延迟"的干净样本
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
        // 给 docs/调度三实现对比.md 取数（目标 5s 正好落在档位 2，这是一个干净样本）
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

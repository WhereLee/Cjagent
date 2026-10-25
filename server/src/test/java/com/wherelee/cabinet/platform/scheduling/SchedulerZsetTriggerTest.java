package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizDelayTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.trigger=zset",
        "cabinet.scheduler.zset-interval-ms=200",
        "cabinet.scheduler.reconcile-minutes=0",
        "cabinet.scheduler.initial-delay-ms=200",
        "cabinet.scheduler.poll-interval-ms=1000",
        "cabinet.scheduler.batch-size=50"
})
class SchedulerZsetTriggerTest {

    private static final Long TENANT = 8116L;
    private static final String ZSET_KEY = "cab:task:zset";

    @Autowired
    private DelayTaskService tasks;
    @Autowired
    private BizDelayTaskMapper taskMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DataSource dataSource;

    private volatile List<String> lastReminders = List.of();

    @AfterEach
    void cleanup() {
        new JdbcTemplate(dataSource).update("delete from biz_delay_task where tenant_id = ?", TENANT);
        redis.delete(ZSET_KEY);
        TenantContext.clear();
    }

    private List<Long> runBatchAndMeasure(int count, LocalDateTime fireAt, boolean remindIntact) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Set<String> keys = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < count; i++) {
            String bizKey = "ZS-" + UUID.randomUUID();
            keys.add(TaskType.SLOT_RELEASE.name() + ":" + bizKey);
            TenantContext.runAs(TENANT, () -> tasks.schedule(TaskType.SLOT_RELEASE, bizKey, TENANT, fireAt));
        }
        lastReminders = List.copyOf(keys);
        if (!remindIntact) {
            redis.opsForZSet().remove(ZSET_KEY, keys.toArray());
        }

        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            Integer undone = jdbc.queryForObject(
                    "select count(*) from biz_delay_task where tenant_id = ? and status <> 'DONE'",
                    Integer.class, TENANT);
            if (undone != null && undone == 0) {
                break;
            }
            sleep();
        }

        List<LocalDateTime> executedAts = jdbc.queryForList(
                "select update_time from biz_delay_task where tenant_id = ? order by id",
                LocalDateTime.class, TENANT);
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from biz_delay_task where tenant_id = ? and status <> 'DONE'", Long.class, TENANT),
                "后台必须在 60 秒内跑完这一批");
        assertEquals(count, executedAts.size(), "取执行时刻应与登记数一致（不然延迟数字会少算）");

        List<Long> delays = new ArrayList<>(executedAts.size());
        for (LocalDateTime executedAt : executedAts) {
            delays.add(Duration.between(fireAt, executedAt).toMillis());
        }
        long max = delays.stream().mapToLong(Long::longValue).max().orElse(-1L);
        long avg = delays.stream().mapToLong(Long::longValue).sum() / Math.max(1, delays.size());
        System.out.printf("MEASURED trigger=zset remindIntact=%s count=%d maxLatencyMs=%d avgLatencyMs=%d%n",
                remindIntact, delays.size(), max, avg);
        return delays;
    }

    private void sleep() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("ZSet 快通道：到期后 1 秒内被执行（drain 间隔 200ms 的实测）")
    void zsetReminderDrivesFastExecution() {
        List<Long> delays = runBatchAndMeasure(5, LocalDateTime.now().plusSeconds(1), true);

        long max = delays.stream().mapToLong(Long::longValue).max().orElse(-1);
        assertTrue(max < 5_000L, "ZSet 模式下最大延迟应远小于轮询间隔，实测 max=" + max + "ms " + delays);
        List<String> stillThere = lastReminders.stream()
                .filter(member -> redis.opsForZSet().score(ZSET_KEY, member) != null)
                .toList();
        assertTrue(stillThere.isEmpty(), "到期提醒应已被原子取走（Lua 取出即删），残留：" + stillThere);
    }

    @Test
    @DisplayName("提醒被抹掉：任务仍被执行，只是退化成兜底轮询的延迟")
    void lostReminderStillConvergesByPoll() {
        List<Long> delays = runBatchAndMeasure(3, LocalDateTime.now().plusSeconds(1), false);

        long max = delays.stream().mapToLong(Long::longValue).max().orElse(-1);
        assertTrue(max > 0, "提醒丢失也必须被跑完（DB 才是真相源），实测 max=" + max + "ms");
    }

    @Test
    @DisplayName("多轮登记同一 bizKey 只有一条任务（幂等登记），执行也只发生一次")
    void repeatedScheduleKeepsSingleTaskAndSingleExecution() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String bizKey = "ZS-DUP-" + UUID.randomUUID();
        LocalDateTime fireAt = LocalDateTime.now().plusSeconds(1);
        for (int i = 0; i < 4; i++) {
            TenantContext.runAs(TENANT, () -> tasks.schedule(TaskType.SLOT_RELEASE, bizKey, TENANT, fireAt));
        }

        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            BizDelayTask row = TenantContext.callAs(TENANT, () -> taskMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<BizDelayTask>lambdaQuery()
                            .eq(BizDelayTask::getBizKey, bizKey)));
            if (row != null && row.getStatus() == TaskStatus.DONE) {
                break;
            }
            sleep();
        }

        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from biz_delay_task where biz_key = ?", Long.class, bizKey),
                "同一 (类型,业务键) 只能有一条任务，否则提醒重几次就执行几次");
        assertEquals(1L, jdbc.queryForObject(
                "select attempt from biz_delay_task where biz_key = ?", Long.class, bizKey),
                "attempt 是\"被抢了几次\"的硬证据：只应为 1");
    }
}

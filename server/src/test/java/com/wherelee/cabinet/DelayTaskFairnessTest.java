package com.wherelee.cabinet;

import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * 调度候选取数的公平性（A-4）。
 *
 * <p>这个类只验一件事，所以夹具故意小到没有点位、没有柜机：延迟任务的候选取数原本是
 * <b>全局按 fire_at 取一批</b>，而批量上限只有 50。这意味着一个租户的到期洪峰能把另一个
 * 租户整批挤掉——而且不报错，看起来只是"那边还没到点"。第 19 刀期间我在共享测试库里
 * 被这个现象坑过一次（上一个测试类留下的到期任务吃光额度，本类的任务"根本没轮到跑"），
 * 所以这条必须有独立用例，不能再靠注释。
 *
 * <p>关掉后台轮询：用例自己调 {@code runDue()}，否则测的是运气不是逻辑。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.batch-size=10",
})
@DisplayName("延迟任务候选取数：按租户公平，大户不吃光整批")
class DelayTaskFairnessTest {

    private static final long CHATTY = 8120L;
    private static final long QUIET = 8121L;
    private static final long CROWD_BASE = 8300L;
    private static final int CROWD = 25;

    @Autowired
    private DelayTaskService tasks;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanStart() {
        jdbc = new JdbcTemplate(dataSource);
        // 两个租户的行都清掉：本用例只关心候选取数，不需要任何业务对象
        jdbc.update("delete from biz_delay_task where tenant_id in (?, ?)", CHATTY, QUIET);
        jdbc.update("delete from biz_delay_task where tenant_id between ? and ?",
                CROWD_BASE, CROWD_BASE + CROWD - 1);
    }

    @Test
    @DisplayName("51 条到期任务的一个租户，不得让另一个租户那一条一轮都轮不到")
    void chattyTenantCannotStarveQuietOne() {
        // 大户 51 条且更早到期：旧的“全局按 fire_at 取 50 条”必定把它挤在批外
        LocalDateTime earlier = LocalDateTime.now().minusMinutes(2);
        TenantContext.runAs(CHATTY, () -> {
            for (int i = 0; i < 51; i++) {
                tasks.schedule(TaskType.COMMAND_RESCAN, "fair-chat-" + i, CHATTY, earlier);
            }
        });
        TenantContext.runAs(QUIET, () ->
                tasks.schedule(TaskType.COMMAND_RESCAN, "fair-quiet-1", QUIET, LocalDateTime.now().minusMinutes(1)));

        // 不包租户上下文：worker 在生产上本来就没有租户（跳租户的取行查询就是这么定的）
        tasks.runDue();

        String status = jdbc.queryForObject(
                "select status from biz_delay_task where tenant_id = ? and biz_key = 'fair-quiet-1'",
                String.class, QUIET);
        // 领走之后是 RUNNING、跑完是 DONE、处理失败是 FAILED——三种都算“轮到它了”；
        // 唯独 PENDING 说明它又被别人的洪峰挤了出去
        boolean claimed = !"PENDING".equals(status);
        org.junit.jupiter.api.Assertions.assertTrue(claimed,
                "安静的租户这一条应当被领走，实际仍是 PENDING：候选取数又被大户吃光了");
    }

    @Test
    @DisplayName("租户数超过批量时轮转起点：第 25 个租户不会永远排在批外")
    void tenantsBeyondBatchAreNotStarvedForever() {
        // 批量 10、25 个租户：不轮转起点时每轮都从 tenant_id 最小的 10 个开始，后 15 个永远轮不到
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        for (int i = 0; i < CROWD; i++) {
            tasks.schedule(TaskType.COMMAND_RESCAN, "fair-crowd-" + i, CROWD_BASE + i, due);
        }

        long last = CROWD_BASE + CROWD - 1;
        String status = "PENDING";
        for (int round = 0; round < 20 && "PENDING".equals(status); round++) {
            tasks.runDue();
            status = jdbc.queryForObject(
                    "select status from biz_delay_task where tenant_id = ? and biz_key = ?",
                    String.class, last, "fair-crowd-" + (CROWD - 1));
        }
        org.junit.jupiter.api.Assertions.assertNotEquals("PENDING", status,
                "租户数超过批量时，靠后的租户也必须被轮转到");
    }
}

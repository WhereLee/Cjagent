package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
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
        jdbc.update("delete from biz_delay_task where tenant_id in (?, ?)", CHATTY, QUIET);
        jdbc.update("delete from biz_delay_task where tenant_id between ? and ?",
                CROWD_BASE, CROWD_BASE + CROWD - 1);
    }

    @Test
    @DisplayName("51 条到期任务的一个租户，不得让另一个租户那一条一轮都轮不到")
    void chattyTenantCannotStarveQuietOne() {
        LocalDateTime earlier = LocalDateTime.now().minusMinutes(2);
        TenantContext.runAs(CHATTY, () -> {
            for (int i = 0; i < 51; i++) {
                tasks.schedule(TaskType.COMMAND_RESCAN, "fair-chat-" + i, CHATTY, earlier);
            }
        });
        TenantContext.runAs(QUIET, () ->
                tasks.schedule(TaskType.COMMAND_RESCAN, "fair-quiet-1", QUIET, LocalDateTime.now().minusMinutes(1)));

        tasks.runDue();

        String status = jdbc.queryForObject(
                "select status from biz_delay_task where tenant_id = ? and biz_key = 'fair-quiet-1'",
                String.class, QUIET);
        boolean claimed = !"PENDING".equals(status);
        org.junit.jupiter.api.Assertions.assertTrue(claimed,
                "安静的租户这一条应当被领走，实际仍是 PENDING：候选取数又被大户吃光了");
    }

    @Test
    @DisplayName("租户数超过批量时轮转起点：第 25 个租户不会永远排在批外")
    void tenantsBeyondBatchAreNotStarvedForever() {
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

package com.wherelee.cabinet;

import com.wherelee.cabinet.application.task.ArchiveService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档搬运（A-5）：只搬该搬的，且不搬丢。
 *
 * <p>这条用例真正要钉住的是两件容易出事的事：
 * ① <b>没到保留期的行不能被搬走</b>（搬错了就是让在线查询突然少数据）；
 * ② <b>删之前必须已经复制成功</b>（"删了但没留下"是这一类任务唯一的灾难形态）。
 * 顺便验幂等：同一个任务重复跑不会把归档表插出两份。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.archive.enabled=true",
        "cabinet.archive.retention-days=90",
        "cabinet.archive.batch-size=100",
})
@DisplayName("归档：过期行搬走、新行留下、重复跑不翻倍")
class ArchiveFlowTest {

    private static final Long TENANT = 8122L;

    @Autowired
    private ArchiveService archive;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from biz_device_report where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_device_report_archive where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_delay_task_archive where tenant_id = ?", TENANT);
    }

    @Test
    @DisplayName("200 天前的行进归档表并从在线表删除；今天的行留在原地")
    void onlyExpiredRowsMoveAndNothingIsLost() {
        insertReport(1L, LocalDateTime.now().minusDays(200));
        insertReport(2L, LocalDateTime.now());
        insertTask("OLD-TASK", "DONE", LocalDateTime.now().minusDays(200));
        insertTask("NEW-TASK", "DONE", LocalDateTime.now());

        ArchiveService.ArchiveResult result = archive.runOnce();

        assertTrue(result.reportsArchived() >= 1, "至少搬走那条过期流水：" + result);
        assertEquals(0, count("select count(*) from biz_device_report where tenant_id = ? and seq = 1", TENANT),
                "过期行应当从在线表消失");
        assertEquals(1, count("select count(*) from biz_device_report_archive where tenant_id = ? and seq = 1", TENANT),
                "同一条必须在归档表里——删除只在它已复制成功时才发生");
        assertEquals(1, count("select count(*) from biz_device_report where tenant_id = ? and seq = 2", TENANT),
                "没到保留期的行不能搬走：在线查询突然少数据比表变大更糟");
        assertEquals(1, count("select count(*) from biz_delay_task_archive where tenant_id = ? and biz_key = 'OLD-TASK'", TENANT),
                "终态任务同样归档");
        assertEquals(1, count("select count(*) from biz_delay_task where tenant_id = ? and biz_key = 'NEW-TASK'", TENANT),
                "未到期的终态任务留在原地");

        // 幂等：再跑一轮不得把归档表插出第二份，也不该报错
        archive.runOnce();
        assertEquals(1, count("select count(*) from biz_device_report_archive where tenant_id = ? and seq = 1", TENANT),
                "重复执行必须不翻倍（insert ignore 按原 id 幂等）");
    }

    private void insertReport(long seq, LocalDateTime createTime) {
        jdbc.update("insert into biz_device_report (id, tenant_id, cabinet_id, slot_id, order_id, seq,"
                        + " event_type, dedup_key, reported_at, received_at, create_time)"
                        + " values (?, ?, ?, ?, ?, ?, 'DOOR_CLOSED', ?, ?, ?, ?)",
                8_812_200_000L + seq, TENANT, 8_812_299L, 8_812_288L, 8_812_277L, seq,
                "archive-" + TENANT + ":" + seq, createTime, createTime, createTime);
    }

    private void insertTask(String bizKey, String status, LocalDateTime createTime) {
        // id 没有自增（雪花由实体层赋值），裸插入必须自己给
        jdbc.update("insert into biz_delay_task (id, tenant_id, task_type, biz_key, fire_at, status, attempt,"
                        + " create_time, update_time, deleted) values (?, ?, 'RECONCILE', ?, ?, ?, 1, ?, ?, 0)",
                8_812_300_000L + Math.abs(bizKey.hashCode() % 9999), TENANT, bizKey, createTime, status,
                createTime, createTime);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}

package com.wherelee.cabinet.assistant;

import com.wherelee.cabinet.assistant.api.AiJobPort;
import com.wherelee.cabinet.assistant.jobs.AiJobRunner;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.AiJobStatus;
import com.wherelee.cabinet.domain.enums.AiJobType;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AI 作业的取证（批 7）。
 *
 * <p>四条覆盖的是"作业化"这个改动的全部承诺：防双击只花一次模型调用、跑完能读到产出、
 * 产出只给提交人看、进程死了别人能接管。
 *
 * <p>跑在桩模式（{@code cabinet.agent.client=stub}）：诊断端口立即返回一段固定报告，
 * 所以这里测的是作业的调度与状态机，不是模型质量——那部分由 {@code RealAgentLiveTest} 负责。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
class AiJobFlowTest {

    private static final Long TENANT = 990000_0001L;
    private static final Long OPERATOR = 990000_0002L;
    private static final Long OTHER = 990000_0003L;

    @Autowired
    private AiJobPort jobs;

    @Autowired
    private AiJobRunner runner;

    @Autowired
    private DataSource dataSource;

    /** 与项目惯例一致：注入 DataSource 自己 new（WorkOrderFlowTest 也这么写）。
     * 批 8 删掉向量库数据源之后容器里只剩一个 JdbcTemplate，这个写法仍然更稳。 */
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareJdbc() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        // 执行器是跨租户扫描的，所以清全表：留着会被下一个用例跑到（outbox 那边踩过同一个坑）
        jdbc.update("delete from biz_ai_job");
    }

    @Test
    @DisplayName("双击复用同一个作业号：同一请求没跑完时只登记一条")
    void doubleClickReusesSameJob() {
        Long first = submitDiagnose();
        Long second = submitDiagnose();

        assertEquals(1, countJobs(), "两次提交后应当只有一条（唯一键裁判，不是先查后插）");
        assertEquals(first, second,
                "同一人重复点必须落到同一个作业号——否则双击就是两次付费的模型调用");
    }

    @Test
    @DisplayName("跑完写产出并释放 active_flag：之后同内容可以再来一次")
    void jobRunsToDoneAndReleasesActiveFlag() {
        Long jobId = submitDiagnose();

        assertEquals(1, runner.runDue(), "本轮应当执行掉这一个作业");

        Map<String, Object> row = jdbc.queryForMap(
                "select status, active_flag, result from biz_ai_job where id = ?", jobId);
        assertEquals(AiJobStatus.DONE.name(), row.get("status"));
        assertNull(row.get("active_flag"),
                "跑完必须释放 active_flag，否则同一请求永远不能再来一次");
        assertNotNull(row.get("result"), "产出一栏必须有东西——前端轮询读的就是它");

        AiJobPort.AiJobView view = TenantContext.callAs(TENANT, () -> jobs.view(jobId, OPERATOR));
        assertEquals("DONE", view.status());
        assertNotNull(view.result(), "视图要带上反序列化后的产出，前端不用自己 parse 字符串");
    }

    @Test
    @DisplayName("别人的作业查不到：AI 产出仅提交人可见")
    void otherUserCannotRead() {
        Long jobId = submitDiagnose();

        assertNull(TenantContext.callAs(TENANT, () -> jobs.view(jobId, OTHER)),
                "不是提交人一律当不存在——不能拿作业号去探测别人看到过什么");
    }

    @Test
    @DisplayName("持有的进程死了：租约过期的 RUNNING 能被接管执行")
    void expiredLeaseIsTakenOver() {
        Long jobId = submitDiagnose();

        // 造“执行中被 kill”：状态是 RUNNING、持有者是一个不会再回来的实例、租约已过期
        jdbc.update("update biz_ai_job set status = 'RUNNING', lease_owner = 'dead-instance', "
                + "lease_expire_at = date_sub(now(3), interval 1 minute) where id = ?", jobId);

        assertEquals(1, runner.runDue(), "租约过期的作业必须能被本实例接过来，否则进程一挂它就永远卡住");
        assertEquals(AiJobStatus.DONE.name(), jdbc.queryForObject(
                "select status from biz_ai_job where id = ?", String.class, jobId));
    }

    private Long submitDiagnose() {
        return TenantContext.callAs(TENANT, () -> jobs.submit(AiJobType.DIAGNOSE, null, OPERATOR));
    }

    private int countJobs() {
        Integer n = jdbc.queryForObject("select count(*) from biz_ai_job", Integer.class);
        return n == null ? 0 : n;
    }
}

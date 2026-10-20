package com.wherelee.cabinet;

import com.wherelee.cabinet.assistant.api.OpsDiagnosePort;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.infrastructure.ai.OpsTools;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 24 刀运维诊断：AI 只读归纳，执行权在人。
 *
 * <p>专设断言三处：<b>真实组合健康可展开</b>（CompositeHealth 构造器包私有，
 * 单测只能测叶子分支，组合递归在这里用真实 HealthEndpoint 验证）；
 * <b>故障台账真 DB 可读</b>（运维问答"最近出过什么故障"的数据源）；
 * <b>桩模式装配可用</b>——AI 未开启时运维拿到明确话术而不是异常。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("运维诊断副驾：AI 只读，执行在人")
class OpsFlowTest {

    private static final Long TENANT = 8166L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private OpsDiagnosePort ops;
    @Autowired
    private HealthEndpoint healthEndpoint;
    @Autowired
    private BizFaultEventMapper faultEvents;

    private JdbcTemplate jdbc;
    private Long siteId;
    private Long cabinetId;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        siteId = ThreadLocalRandom.current().nextLong(82_6100_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '运维诊断测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "OD-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, '运维诊断测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-OD-" + suffix);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_fault_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
        TenantContext.clear();
    }

    @Test
    @DisplayName("桩模式装配可用：AI 未开启时运维拿到明确话术而不是异常")
    void stubDiagnosisGuidesToExistingTools() {
        OpsDiagnosePort.OpsReport report = TenantContext.callAs(TENANT, () -> ops.diagnose());

        assertNotNull(report);
        assertTrue(report.healthSummary().contains("未开启"), report.healthSummary());
        assertNotNull(report.logFindings(), "四件结构要完整（即便桩态）");
        assertNotNull(report.crashArtifacts(), "四件结构要完整（即便桩态）");
        assertNotNull(report.advice(), "四件结构要完整（即便桩态）");

        OpsDiagnosePort.OpsAnswer answer = TenantContext.callAs(TENANT,
                () -> ops.ask("最近出过什么故障"));
        assertTrue(answer.answer().contains("未开启"), answer.answer());
    }

    @Test
    @DisplayName("真实组合健康可展开：db/redis 等组件状态进文本（组合体递归的真实验证）")
    void realCompositeHealthFlattens() {
        OpsTools tools = new OpsTools(healthEndpoint, faultEvents, "logs");

        String out = TenantContext.callAs(TENANT, tools::systemHealth);

        assertTrue(out.contains("整体="), out);
        assertTrue(out.contains("db=") && out.contains("redis="), out);
    }

    @Test
    @DisplayName("故障台账真 DB 可读：插入的故障事件按格口与动作列出")
    void faultLedgerReadsRealLedger() {
        jdbc.update("insert into biz_fault_event (id, tenant_id, cabinet_id, slot_id, fault_type, action, "
                + "fail_count, reason, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'OPEN_FAILED', 'MARK_UNAVAILABLE', 3, '运维诊断夹具：连续开门失败', NOW(3), NOW(3), 0)",
                siteId + 9, TENANT, cabinetId, siteId + 2);

        OpsTools tools = new OpsTools(healthEndpoint, faultEvents, "logs");
        String out = TenantContext.callAs(TENANT, tools::faultLedger);

        assertTrue(out.contains(String.valueOf(siteId + 2)), out);
        assertTrue(out.contains("MARK_UNAVAILABLE"), out);
        assertEquals(1, TenantContext.callAs(TENANT, () ->
                        faultEvents.selectList(null)).size(),
                "夹具就一条：台账读的是同一来源");
    }
}

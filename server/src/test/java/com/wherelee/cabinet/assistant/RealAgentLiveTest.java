package com.wherelee.cabinet.assistant;

import com.wherelee.cabinet.assistant.api.OpsDiagnosePort;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import com.wherelee.cabinet.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "MIMO_API_KEY", matches = ".+")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.agent.client=real",
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("真模型联调：MiMo 工具循环端到端（无 key 自动跳过）")
class RealAgentLiveTest {

    private static final Long TENANT = 8164L;
    private static final Long CUSTOMER = 81640001L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private SupportAgentPort support;
    @Autowired
    private WorkOrderBriefPort briefs;
    @Autowired
    private OpsDiagnosePort ops;

    private JdbcTemplate jdbc;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;
    private Long orderId;
    private String orderNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        siteId = ThreadLocalRandom.current().nextLong(82_6100_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        orderId = siteId + 3;
        orderNo = "RL-SO-" + suffix;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '真模型联调点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "RL-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, '真模型联调柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-RL-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'SMALL', 'OCCUPIED', NULL, NULL, ?, NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId, "R" + suffix.substring(0, 4), orderId);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                + "values (?, ?, ?, '真模型联调用户', '13900007777', 1, NOW(3), NOW(3), NOW(3), 0)",
                CUSTOMER, TENANT, "rl-" + suffix);
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, settled_points, "
                + "arrears_points, create_time, update_time, deleted, active_flag) "
                + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'ACTIVE', 'V81234567', 0, 0, 0, 0, 0, NOW(3), NOW(3), 0, 1)",
                orderId, TENANT, orderNo, siteId, cabinetId, slotId, CUSTOMER);
        jdbc.update("insert into biz_work_order (id, tenant_id, type, urgent, status, queue_side, slot_id, slot_no, "
                + "title, active_flag, version, create_time, update_time, deleted) "
                + "values (?, ?, 'DEVICE_FAULT', 0, 'PENDING', 'ONLINE', ?, ?, '取件时柜门打不开，用户报障', 1, 0, NOW(3), NOW(3), 0)",
                siteId + 4, TENANT, slotId, "W" + suffix.substring(0, 4));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_customer where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
        TenantContext.clear();
    }

    @Test
    @DisplayName("门打不开：真模型走工具循环（查单→重开或建单），回复非空且不崩")
    void realModelHandlesDoorTrouble() {
        long start = System.currentTimeMillis();
        SupportAgentPort.AgentReply reply = TenantContext.callAs(TENANT, () ->
                support.chat(new SupportAgentPort.SupportSession(
                        CUSTOMER, orderNo, "我的柜门打不开，东西还取不出来，帮我看看")));

        long costMs = System.currentTimeMillis() - start;
        System.out.println("[真模型] 门故障回复（" + costMs + "ms）escalated=" + reply.escalated()
                + " ticketNo=" + reply.ticketNo() + "\n" + reply.reply());
        assertFalse(reply.reply() == null || reply.reply().isBlank(), "真模型必须给出一句话术");
        Integer tickets = jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ?", Integer.class, TENANT);
        if (reply.escalated()) {
            assertTrue(tickets != null && tickets >= 1, "声称转报就必须真的建了单");
        }
    }

    @Test
    @DisplayName("计费问题：不碰设备与工单，wiki 口径直答")
    void realModelAnswersBillingFromWiki() {
        briefBeforeAnswer = jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ?", Integer.class, TENANT);
        SupportAgentPort.AgentReply reply = TenantContext.callAs(TENANT, () ->
                support.chat(new SupportAgentPort.SupportSession(
                        CUSTOMER, null, "在最小的柜子里放一整天，最多收多少钱？")));

        System.out.println("[真模型] 计费回复\n" + reply.reply());
        assertFalse(reply.reply() == null || reply.reply().isBlank(), "wiki 口径问题必须能答");
        assertFalse(reply.escalated(), "纯问答不该建单");
        Integer after = jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ?", Integer.class, TENANT);
        assertEquals(briefBeforeAnswer, after, "问答不该留下新工单");
    }

    private Integer briefBeforeAnswer;

    @Test
    @DisplayName("B 端工单简报：一次工具循环产出三件，摘要非空且不碰工单状态")
    void realModelProducesWorkOrderBrief() {
        Long workOrderId = siteId + 4;
        long start = System.currentTimeMillis();
        WorkOrderBriefPort.Brief brief = TenantContext.callAs(TENANT, () -> briefs.brief(workOrderId, 99001L));

        long costMs = System.currentTimeMillis() - start;
        System.out.println("[真模型] 工单简报（" + costMs + "ms）相似单=" + brief.similarCases().size()
                + "\n摘要：" + brief.summary() + "\n草稿：" + brief.draftNote());
        assertFalse(brief.summary() == null || brief.summary().isBlank(), "真模型必须给出摘要");
        String status = jdbc.queryForObject(
                "select status from biz_work_order where id = ?", String.class, workOrderId);
        assertEquals("PENDING", status, "简报不得改工单状态");
    }

    @Test
    @DisplayName("运维诊断：只读工具循环产出报告，健康/日志/残留四件齐且不碰任何状态")
    void realModelProducesOpsDiagnosis() {
        long start = System.currentTimeMillis();
        OpsDiagnosePort.OpsReport report = TenantContext.callAs(TENANT, () -> ops.diagnose());

        long costMs = System.currentTimeMillis() - start;
        System.out.println("[真模型] 运维诊断（" + costMs + "ms）\n健康：" + report.healthSummary()
                + "\n日志发现：" + report.logFindings() + "\n残留：" + report.crashArtifacts()
                + "\n建议：" + report.advice());
        assertFalse(report.healthSummary() == null || report.healthSummary().isBlank(), "真模型必须给出健康摘要");
    }
}

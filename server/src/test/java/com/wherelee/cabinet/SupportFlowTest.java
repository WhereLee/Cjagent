package com.wherelee.cabinet;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.application.support.SupportAgentPort;
import com.wherelee.cabinet.application.workorder.WorkOrderIntake;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.infrastructure.ai.AgentTools;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 22 刀智能客服：AI 只递话，规则全部复用既有服务。
 *
 * <p>专设断言集中在三处安全底线：<b>桩模式永不触付费接口</b>（端到端拿到的是引导话术）；
 * <b>AI 建单走 WorkOrderIntake 统一入口</b>（快照补齐、幂等合并在真 DB 下成立）；
 * <b>归属守卫</b>（别人的单一律"未找到"，模型连探测的口子都没有）。
 * 工具的穷举语义（重开一次/类型白名单）在 {@code AgentToolsTest} 单测覆盖。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("智能客服：AI 递话，规则复用")
class SupportFlowTest {

    private static final Long TENANT = 8163L;
    private static final Long CUSTOMER = 81630001L;
    private static final Long FOREIGN = 81630002L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private SupportAgentPort support;
    @Autowired
    private StorageQueryService queries;
    @Autowired
    private StorageOrderFacade doors;
    @Autowired
    private WorkOrderIntake intake;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private BizWorkOrderMapper workOrderMapper;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private Long slotId;
    private Long orderId;
    private String orderNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Long siteId = ThreadLocalRandom.current().nextLong(82_6100_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        orderId = siteId + 3;
        orderNo = "SP-SO-" + suffix;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '智能客服测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "SP-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, '智能客服测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-SP-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'SMALL', 'OCCUPIED', NULL, NULL, ?, NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId, "S" + suffix.substring(0, 4), orderId);
        for (Long customerId : new Long[]{CUSTOMER, FOREIGN}) {
            jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                    + "values (?, ?, ?, '客服测试用户', '13900008888', 1, NOW(3), NOW(3), NOW(3), 0)",
                    customerId, TENANT, "sp-" + suffix + "-" + customerId);
        }
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, settled_points, "
                + "arrears_points, estimate_minutes, create_time, update_time, deleted, active_flag) "
                + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'ACTIVE', 'V91234567', 0, 0, 0, 0, 0, 240, NOW(3), NOW(3), 0, 1)",
                orderId, TENANT, orderNo, siteId, cabinetId, slotId, CUSTOMER);
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

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    @Test
    @DisplayName("桩模式端到端：AI 未开启时给固定引导，不触模型也不报错")
    void stubAgentGuidesToExistingEntries() {
        SupportAgentPort.AgentReply reply = TenantContext.callAs(TENANT, () ->
                support.chat(new SupportAgentPort.SupportSession(CUSTOMER, orderNo, "柜门打不开")));

        assertFalse(reply.escalated(), "桩不建单");
        assertTrue(reply.reply().contains("未开启"), reply.reply());
    }

    @Test
    @DisplayName("AI 转报走统一开单入口：快照补齐、类型落库正确；重复转报幂等合并")
    void raiseTicketGoesThroughIntakeAndMergesIdempotently() {
        AgentTools tools = new AgentTools(queries, doors, intake, orderMapper, workOrderMapper,
                new com.wherelee.cabinet.application.workorder.WorkOrderHandoffService(),
                new SupportAgentPort.SupportSession(CUSTOMER, orderNo, "取件时门打不开"));
        String attempt = TenantContext.callAs(TENANT, tools::retryMyDoor);
        assertTrue(attempt.contains("开门"), attempt);
        String first = TenantContext.callAs(TENANT, () ->
                tools.raiseTicket("DEVICE_FAULT", "用户报取件时柜门打不开；已远程重开一次，无回执；疑似锁故障"));
        assertTrue(first.contains("转报成功"), first);
        assertTrue(tools.escalated(), "转报成功要置升级标记");

        List<Map<String, Object>> orders = rows(
                "select id, kind, source, type, queue_side, priority, title, snapshot_json, "
                        + "cast(handoff_json as char) as handoff_json "
                        + "from biz_work_order where tenant_id = ? and slot_id = ?", TENANT, slotId);
        assertEquals(1, orders.size(), "AI 建单一张，不多开");
        assertEquals("REQUEST", orders.get(0).get("kind"), "AI 转报是用户请求，不是设备异常");
        assertEquals("AI", orders.get(0).get("source"));
        assertEquals("DEVICE_FAULT", orders.get(0).get("type"));
        assertEquals("ONLINE", orders.get(0).get("queue_side"), "AI 只建线上单：派线下是业务员的决策");
        assertTrue(String.valueOf(orders.get(0).get("title")).contains("AI 客服转报"),
                String.valueOf(orders.get(0).get("title")));
        assertTrue(String.valueOf(orders.get(0).get("snapshot_json")).contains("已远程重开一次"),
                "备注要带进快照的检测值：" + orders.get(0).get("snapshot_json"));
        assertTrue(String.valueOf(orders.get(0).get("snapshot_json")).contains(orderNo),
                "现场快照由统一入口补齐，不靠 agent 抄");
        String handoff = String.valueOf(orders.get(0).get("handoff_json"));
        assertTrue(handoff.contains("attempt") && handoff.contains("nextStep"),
                "交接包必须带已尝试动作与建议下一步：" + handoff);
        assertTrue(handoff.contains("用户报取件时") || handoff.contains("用户再次报"),
                "交接包必须带用户原话：" + handoff);
        Long ticketId = ((Number) orders.get(0).get("id")).longValue();
        assertEquals(ticketId.toString(), tools.ticketNo());
        Map<String, Object> created = jdbc.queryForMap(
                "select actor_type, trace_id from biz_work_order_event "
                        + "where work_order_id = ? and event_type = 'CREATED'", ticketId);
        assertEquals("AI", created.get("actor_type"), "AI 动作不能混成人工/系统动作");
        assertNotNull(created.get("trace_id"), "AI 动作必须带对话追踪号");

        // 再转一次：幂等合并到同一张，记 REPEATED，不重复建
        AgentTools again = new AgentTools(queries, doors, intake, orderMapper, workOrderMapper,
                new com.wherelee.cabinet.application.workorder.WorkOrderHandoffService(),
                new SupportAgentPort.SupportSession(CUSTOMER, orderNo, "还是打不开"));
        TenantContext.runAs(TENANT, again::retryMyDoor);
        String merged = TenantContext.callAs(TENANT, () ->
                again.raiseTicket("DEVICE_FAULT", "用户再次报柜门打不开"));
        assertTrue(merged.contains(String.valueOf(ticketId)), "幂等合并要返回同一张单：" + merged);
        assertEquals(1, rows("select id from biz_work_order where tenant_id = ? and slot_id = ?", TENANT, slotId).size());
        assertEquals(1, rows("select id from biz_work_order_event where tenant_id = ? and event_type = 'REPEATED'",
                TENANT).size(), "合并要留痕：同一异常再次被观察到");
    }

    @Test
    @DisplayName("归属守卫：别人的单转报一律“未找到”，一张单都不建")
    void raiseTicketRejectsForeignOrder() {
        AgentTools intruder = new AgentTools(queries, doors, intake, orderMapper, workOrderMapper,
                new com.wherelee.cabinet.application.workorder.WorkOrderHandoffService(),
                new SupportAgentPort.SupportSession(FOREIGN, orderNo, "这是我的柜子"));

        String out = TenantContext.callAs(TENANT, () -> intruder.raiseTicket("DEVICE_FAULT", "门坏了"));

        assertTrue(out.contains("未找到"), out);
        assertFalse(intruder.escalated());
        assertEquals(0, rows("select id from biz_work_order where tenant_id = ?", TENANT).size(),
                "归属校验不过就一张单都不许有");
    }
}

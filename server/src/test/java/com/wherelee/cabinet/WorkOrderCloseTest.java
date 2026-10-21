package com.wherelee.cabinet;

import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关闭闭环（批次 6）：关闭依据要能被事实支撑，用户能确认/重开，自动关闭要通知到位。
 *
 * <p>关掉后台轮询：用例自己调 {@code autoClose()}，否则测的是调度器的运气。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("工单关闭闭环：依据、用户确认/重开、自动关闭通知")
class WorkOrderCloseTest {

    private static final Long TENANT = 8171L;
    private static final Long OPERATOR = 81710001L;
    private static final Long CUSTOMER = 81710002L;

    @Autowired
    private WorkOrderService workOrders;
    @Autowired
    private DelayTaskService tasks;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;
    private String cabinetNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_notice where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);

        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        siteId = ThreadLocalRandom.current().nextLong(91_7000_0000L, 99_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        cabinetNo = "CAB-CLOSE-" + suffix;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '关闭闭环测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "CLOSE-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, '关闭闭环测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, cabinetNo);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, create_time, update_time, deleted) "
                        + "values (?, ?, ?, 'S01', 'SMALL', 'FREE', NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_notice where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where id = ?", siteId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("用户确认已解决：写 USER_CONFIRMED，关闭依据是用户本人")
    void userConfirmationWritesUserConfirmed() {
        Long id = openRequest(WorkOrderKind.REQUEST);

        TenantContext.runAs(TENANT, () -> workOrders.confirmResolved(id, CUSTOMER, "门已经能开了"));

        Map<String, Object> row = jdbc.queryForMap(
                "select status, close_reason, result_note from biz_work_order where id = ?", id);
        assertEquals("RESOLVED", row.get("status"));
        assertEquals("USER_CONFIRMED", row.get("close_reason"));
        assertTrue(String.valueOf(row.get("result_note")).contains("门已经能开了"));
        assertEquals("USER", jdbc.queryForObject(
                "select actor_type from biz_work_order_event where work_order_id = ? and event_type = 'USER_CONFIRMED'",
                String.class, id), "用户确认不能记成系统/人工动作");
        assertEquals(0, taskCount(id), "用户确认关闭后不能再留下 SLA 到点任务");
    }

    @Test
    @DisplayName("业务员不能代写用户确认：关闭依据只能由用户产生")
    void operatorCannotWriteUserConfirmed() {
        Long id = openRequest(WorkOrderKind.REQUEST);

        BizException refused = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> workOrders.resolve(id, OPERATOR, "应该修好了", false, CloseReason.USER_CONFIRMED)));
        assertTrue(refused.getMessage().contains("只能由用户"), refused.getMessage());
        assertEquals("PENDING", jdbc.queryForObject(
                "select status from biz_work_order where id = ?", String.class, id),
                "被拒绝的关闭依据不能把工单关掉");
    }

    @Test
    @DisplayName("事实恢复必须有现场事实：异常未解除时只能按人工结论关闭")
    void factRecoveredRequiresAnomalyCleared() {
        jdbc.update("update biz_compartment set anomaly = 'DOOR_OPEN', anomaly_at = NOW(3), "
                + "anomaly_reason = '测试：门未关' where id = ?", slotId);
        Long id = openRequest(WorkOrderKind.INCIDENT);

        BizException refused = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> workOrders.resolve(id, OPERATOR, "应该好了", false, CloseReason.FACT_RECOVERED)));
        assertTrue(refused.getMessage().contains("尚未解除"), refused.getMessage());

        jdbc.update("update biz_compartment set anomaly = null, anomaly_at = null, anomaly_reason = null where id = ?", slotId);
        TenantContext.runAs(TENANT,
                () -> workOrders.resolve(id, OPERATOR, "现场确认异常已解除", false, CloseReason.FACT_RECOVERED));
        assertEquals("FACT_RECOVERED", jdbc.queryForObject(
                "select close_reason from biz_work_order where id = ?", String.class, id));
    }

    @Test
    @DisplayName("用户反馈还没解决：重开本人已关闭的单，次数 +1 并重新登记 SLA")
    void reopenIncrementsCountAndReschedulesSla() {
        Long id = openRequest(WorkOrderKind.REQUEST);
        TenantContext.runAs(TENANT, () -> workOrders.resolve(id, OPERATOR, "已调整", false));

        TenantContext.runAs(TENANT, () -> workOrders.reopen(id, CUSTOMER, "还是打不开"));

        Map<String, Object> row = jdbc.queryForMap(
                "select status, close_reason, closed_at, reopened_count from biz_work_order where id = ?", id);
        assertEquals("PENDING", row.get("status"));
        assertNull(row.get("close_reason"));
        assertNull(row.get("closed_at"));
        assertEquals(1, ((Number) row.get("reopened_count")).intValue());
        assertEquals("REOPENED", jdbc.queryForObject(
                "select event_type from biz_work_order_event where work_order_id = ? and event_type = 'REOPENED'",
                String.class, id));
        assertEquals(2, taskCount(id), "重开后响应与解决两个 SLA 任务都要重新登记");
        assertEquals("PENDING", jdbc.queryForObject(
                "select status from biz_delay_task where tenant_id = ? and task_type = ? and biz_key = ?",
                String.class, TENANT, TaskType.WORK_ORDER_RESOLVE_SLA.name(), String.valueOf(id)));
    }

    @Test
    @DisplayName("重开撞上同格口未关闭单：并入那张并记 MERGED，不让用户反馈丢失")
    void reopenMergesIntoExistingOpenOrder() {
        Long oldId = openRequest(WorkOrderKind.INCIDENT);
        TenantContext.runAs(TENANT, () -> workOrders.resolve(oldId, OPERATOR, "先关了", false));
        Long currentId = openRequest(WorkOrderKind.INCIDENT);
        assertTrue(!oldId.equals(currentId));

        Long target = TenantContext.callAs(TENANT, () -> workOrders.reopen(oldId, CUSTOMER, "又出现了"));

        assertEquals(currentId, target, "应并入当前未关闭的那张");
        Map<String, Object> old = jdbc.queryForMap(
                "select status, close_reason, merged_into_id, reopened_count from biz_work_order where id = ?", oldId);
        assertEquals("RESOLVED", old.get("status"));
        assertEquals("MERGED", old.get("close_reason"));
        assertEquals(currentId, ((Number) old.get("merged_into_id")).longValue());
        assertEquals(0, ((Number) old.get("reopened_count")).intValue(), "合并不是重开，次数不增加");
    }

    @Test
    @DisplayName("事实自行消失：写 FACT_RECOVERED，同时通知用户与队列")
    void autoCloseWritesFactRecoveredAndNotifiesBothSides() {
        jdbc.update("update biz_compartment set anomaly = 'DOOR_OPEN', anomaly_at = NOW(3), "
                + "anomaly_reason = '测试：门未关' where id = ?", slotId);
        Long id = openRequest(WorkOrderKind.INCIDENT);

        Integer closed = TenantContext.callAs(TENANT,
                () -> workOrders.autoClose(slotId, WorkOrderType.DOOR_NOT_CLOSED));

        assertEquals(1, closed);
        Map<String, Object> row = jdbc.queryForMap(
                "select status, close_reason from biz_work_order where id = ?", id);
        assertEquals("AUTO_CLOSED", row.get("status"));
        assertEquals("FACT_RECOVERED", row.get("close_reason"));

        List<Map<String, Object>> notices = jdbc.queryForList(
                "select channel, customer_id, content from biz_notice where tenant_id = ? and work_order_id = ?",
                TENANT, id);
        assertEquals(2, notices.size(), "自动关闭要同时给用户和队列各一条通知");
        Map<String, Object> toUser = notices.stream()
                .filter(n -> "SYSTEM_MESSAGE".equals(n.get("channel"))).findFirst().orElseThrow();
        Map<String, Object> toQueue = notices.stream()
                .filter(n -> "QUEUE".equals(n.get("channel"))).findFirst().orElseThrow();
        assertEquals(CUSTOMER, ((Number) toUser.get("customer_id")).longValue());
        assertTrue(String.valueOf(toUser.get("content")).contains("自动关闭"));
        assertNull(toQueue.get("customer_id"), "队列通知不能挂到用户消息里");
        assertNotNull(toQueue.get("content"));
        assertEquals(0, taskCount(id), "自动关闭后不能再留下 SLA 到点任务");
    }

    @Test
    @DisplayName("用户只能看/动自己的工单")
    void customerCannotTouchOthersOrder() {
        Long id = openRequest(WorkOrderKind.REQUEST);
        Long stranger = CUSTOMER + 999;

        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> workOrders.myOrder(id, stranger)));
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> workOrders.confirmResolved(id, stranger, "不是我")));
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> workOrders.reopen(id, stranger, "不是我")));
        assertEquals("PENDING", jdbc.queryForObject(
                "select status from biz_work_order where id = ?", String.class, id));
    }

    @Test
    @DisplayName("用户侧列表只返回自己的工单，且不带内部交接字段")
    void myOrdersOnlyOwn() {
        Long id = openRequest(WorkOrderKind.REQUEST);
        jdbc.update("insert into biz_work_order (id, tenant_id, order_no, customer_id, kind, source, type, "
                        + "status, queue_side, urgent, priority, title, create_time, update_time, deleted) "
                        + "values (?, ?, 'OTHER-1', ?, 'REQUEST', 'AI', 'DEVICE_FAULT', 'PENDING', 'ONLINE', 0, "
                        + "'P3', '别人的单', NOW(3), NOW(3), 0)",
                siteId + 99, TENANT, CUSTOMER + 1);

        List<WorkOrderService.CustomerView> mine = TenantContext.callAs(TENANT,
                () -> workOrders.myOrders(CUSTOMER, 20));

        assertEquals(1, mine.size());
        assertEquals(id, mine.get(0).id());
        assertEquals(WorkOrderStatus.PENDING.name(), mine.get(0).status());
    }

    private Long openRequest(WorkOrderKind kind) {
        WorkOrderType type = kind == WorkOrderKind.INCIDENT
                ? WorkOrderType.DOOR_NOT_CLOSED : WorkOrderType.DEVICE_FAULT;
        return TenantContext.callAs(TENANT, () -> workOrders.open(new WorkOrderService.NewOrderV2(
                TENANT, kind, type, WorkOrderSource.AI,
                WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderCategory.DOOR_FAULT, false,
                WorkOrderPriority.P2, WorkOrderImpact.SINGLE, WorkOrderUrgency.HIGH,
                "ONLINE", WorkOrderService.SIDE_ONLINE,
                siteId, cabinetId, slotId, "S01",
                "CLOSE-SO-" + slotId, null, CUSTOMER, "13800005678",
                "关闭闭环测试点位", cabinetNo,
                "关闭闭环测试工单", "门=已关",
                LocalDateTime.now().plusMinutes(480),
                LocalDateTime.now().plusMinutes(30),
                LocalDateTime.now().plusMinutes(480), null, null)));
    }

    private int taskCount(Long workOrderId) {
        return jdbc.queryForObject("select count(*) from biz_delay_task where tenant_id = ? and biz_key = ? "
                        + "and status = 'PENDING'",
                Integer.class, TENANT, String.valueOf(workOrderId));
    }
}

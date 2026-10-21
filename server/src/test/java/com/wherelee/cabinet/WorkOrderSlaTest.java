package com.wherelee.cabinet;

import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单 SLA（批次 4）：双时限、到点待办、暂停/恢复，以及优先级排序。
 *
 * <p>关掉后台轮询：用例自己调 {@code runDue()}，否则测的是调度器的运气。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("工单 SLA：两个时限、超时待办、暂停时长不计入")
class WorkOrderSlaTest {

    private static final Long TENANT = 8170L;
    private static final Long OPERATOR = 81700001L;

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
        siteId = ThreadLocalRandom.current().nextLong(81_7000_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        cabinetNo = "CAB-SLA-" + suffix;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                        + "values (?, ?, ?, 'SLA 测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "SLA-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, 'SLA 测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
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
    @DisplayName("开单登记两个时限；认领算响应并取消响应任务；关闭取消解决任务")
    void claimAndResolveSettleSlaTasks() {
        Long id = openRequest(WorkOrderType.DEVICE_FAULT, WorkOrderPriority.P2,
                LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusMinutes(480));

        assertEquals(2, taskCount(id), "开单必须同时登记响应与解决两个任务");
        assertEquals("PENDING", task(id, TaskType.WORK_ORDER_RESPONSE_SLA).get("status"));

        TenantContext.runAs(TENANT, () -> workOrders.claim(id, OPERATOR));
        Map<String, Object> response = task(id, TaskType.WORK_ORDER_RESPONSE_SLA);
        assertEquals("DONE", response.get("status"));
        assertEquals("CANCELLED", response.get("last_error"), "认领即响应，响应任务不再需要");
        assertNotNull(jdbc.queryForObject("select first_response_at from biz_work_order where id = ?",
                LocalDateTime.class, id), "认领必须记录首次响应时刻");

        TenantContext.runAs(TENANT, () -> workOrders.resolve(id, OPERATOR, "已处理完", false));
        Map<String, Object> resolve = task(id, TaskType.WORK_ORDER_RESOLVE_SLA);
        assertEquals("DONE", resolve.get("status"));
        assertEquals("CANCELLED", resolve.get("last_error"), "工单关闭后解决任务必须取消");
    }

    @Test
    @DisplayName("到点没人响应/没结论：两个任务各生成一条待办，不改工单状态")
    void breachEmitsStaffTodoWithoutClosingOrder() {
        Long id = openRequest(WorkOrderType.DEVICE_FAULT, WorkOrderPriority.P2,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().minusMinutes(1));

        tasks.runDue();

        Integer notices = jdbc.queryForObject(
                "select count(*) from biz_notice where tenant_id = ? and work_order_id = ? "
                        + "and kind in ('SLA_RESPONSE','SLA_RESOLVE')", Integer.class, TENANT, id);
        assertEquals(2, notices, "响应与解决到点都要进待办，不能只提醒一次");
        assertEquals("PENDING", jdbc.queryForObject(
                "select status from biz_work_order where id = ?", String.class, id),
                "SLA 超时只催办，不能替人关闭工单");
    }

    @Test
    @DisplayName("暂停期间不计入 SLA：恢复时两个时限按暂停时长顺延，并重新登记任务")
    void pauseAndResumeExtendDeadlines() {
        Long id = openRequest(WorkOrderType.DEVICE_FAULT, WorkOrderPriority.P2,
                LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusMinutes(480));
        LocalDateTime responseBefore = jdbc.queryForObject(
                "select response_due_at from biz_work_order where id = ?", LocalDateTime.class, id);

        TenantContext.runAs(TENANT, () -> workOrders.pauseSla(id, OPERATOR));
        assertNotNull(jdbc.queryForObject("select sla_paused_at from biz_work_order where id = ?",
                LocalDateTime.class, id), "暂停必须留下起点");
        assertEquals("DONE", task(id, TaskType.WORK_ORDER_RESPONSE_SLA).get("status"),
                "暂停中不得到点催办");

        // 模拟已经暂停了 30 分钟：把起点拨回去，恢复时应当顺延 30 分钟
        jdbc.update("update biz_work_order set sla_paused_at = date_sub(now(3), interval 30 minute) where id = ?", id);
        TenantContext.runAs(TENANT, () -> workOrders.resumeSla(id, OPERATOR));

        LocalDateTime responseAfter = jdbc.queryForObject(
                "select response_due_at from biz_work_order where id = ?", LocalDateTime.class, id);
        long extended = java.time.Duration.between(responseBefore, responseAfter).toMinutes();
        assertTrue(extended >= 29 && extended <= 30, "暂停 30 分钟就应顺延 30 分钟，实际 " + extended);
        Integer pausedMinutes = jdbc.queryForObject(
                "select sla_paused_minutes from biz_work_order where id = ?", Integer.class, id);
        assertTrue(pausedMinutes >= 29, "累计暂停分钟数要留下：" + pausedMinutes);

        Map<String, Object> responseTask = task(id, TaskType.WORK_ORDER_RESPONSE_SLA);
        assertEquals("PENDING", responseTask.get("status"), "恢复后必须重新登记到点任务");
        LocalDateTime fireAt = (LocalDateTime) responseTask.get("fire_at");
        assertTrue(fireAt.isAfter(LocalDateTime.now()), "恢复后的到点时间必须落在未来：" + fireAt);
        assertEquals(2, taskCount(id));
    }

    @Test
    @DisplayName("队列按优先级排序：整柜离线（P1）排在单格门打不开（P2）前面")
    void queueOrdersByPriority() {
        Long p2 = openRequest(WorkOrderType.DEVICE_FAULT, WorkOrderPriority.P2,
                LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusMinutes(480));
        Long p1 = openRequest(WorkOrderType.DOOR_NOT_CLOSED, WorkOrderPriority.P1,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusMinutes(120));

        PageResult<WorkOrderConsolePort.View> queue = TenantContext.callAs(TENANT,
                () -> workOrders.queue(WorkOrderService.SIDE_ONLINE, true, new PageQuery()));
        assertEquals(p1, queue.records().get(0).id(), "P1 必须排在 P2 前面，而不是按开单先后");
        assertEquals(p2, queue.records().get(1).id());
    }

    private Long openRequest(WorkOrderType type, WorkOrderPriority priority,
                             LocalDateTime responseDue, LocalDateTime resolveDue) {
        return TenantContext.callAs(TENANT, () -> workOrders.open(new WorkOrderConsolePort.NewOrderV2(
                TENANT, WorkOrderKind.REQUEST, type, WorkOrderSource.AI,
                WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderCategory.DOOR_FAULT, false,
                priority, WorkOrderImpact.SINGLE, WorkOrderUrgency.HIGH,
                "ONLINE", WorkOrderService.SIDE_ONLINE,
                siteId, cabinetId, slotId, "S01",
                "SLA-SO-" + slotId, null, null, "13800001234",
                "SLA 测试点位", cabinetNo,
                "SLA 测试工单", "门=已关", resolveDue, responseDue, resolveDue, null, null)));
    }

    private int taskCount(Long workOrderId) {
        return jdbc.queryForObject("select count(*) from biz_delay_task where tenant_id = ? and biz_key = ?",
                Integer.class, TENANT, String.valueOf(workOrderId));
    }

    private Map<String, Object> task(Long workOrderId, TaskType type) {
        return jdbc.queryForMap("select status, last_error, fire_at from biz_delay_task "
                        + "where tenant_id = ? and task_type = ? and biz_key = ?",
                TENANT, type.name(), String.valueOf(workOrderId));
    }
}

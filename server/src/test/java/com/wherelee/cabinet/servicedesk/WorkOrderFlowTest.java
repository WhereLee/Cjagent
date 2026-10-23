package com.wherelee.cabinet.servicedesk;

import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.bff.admin.workorder.AdminWorkOrderController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.wherelee.cabinet.locker.jobs.OutboxRelay;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 19B 工单流转：开单幂等、级别映射、认领、派线下、自动转化留底。
 *
 * <p>这里最容易被写歪的两件事，都被单独设了用例：
 * ① 同一异常被反复观察只能有一张未关闭工单（否则队列长度失去意义）；
 * ② 自动转化<b>不能</b>记成人工结论，也不能删（用户明确要求留底）。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("工单流转：一张异常单的一生")
class WorkOrderFlowTest {

    private static final Long TENANT = 8161L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private CompartmentStateService states;
    @Autowired
    private WorkOrderService workOrders;
    @Autowired
    private com.wherelee.cabinet.notify.api.NoticePort notices;
    @Autowired
    private com.wherelee.cabinet.servicedesk.app.WorkOrderIntake intake;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private com.wherelee.cabinet.servicedesk.app.WorkOrderTriageService triage;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private Long slotId;
    private Long orderId;
    private String slotNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // 主键不能从 8 位随机码再取模 9 万算：桶太少，跨类跨轮必撞（实测撞过一次 DuplicateKey，
        // 表现为完完全全不相干的用例红）。“测试数据不相互碰撞”得靠区间而不是靠运气
        Long siteId = java.util.concurrent.ThreadLocalRandom.current().nextLong(81_6100_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        slotNo = "S" + suffix.substring(0, 4);
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '工单测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "WO-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, '工单测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-WO-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'SMALL', 'OCCUPIED', NOW(3), NULL, ?, NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId, slotNo, siteId + 3);
        // 一个带电话的客户 + 一张绑在这个格口上的活动单：工单要快照“当时能联系到谁”
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                + "values (?, ?, ?, '工单客户', ?, 1, NOW(3), NOW(3), NOW(3), 0)",
                81619999L, TENANT, "wo-" + suffix, "13800001234");
        orderId = siteId + 3;
        assertEquals(orderId, jdbc.queryForObject("select current_order_id from biz_compartment where id = ?", Long.class, slotId),
                "夹具必须把格口绑到单上：工单快照里的电话就是从这条活动单回查客户的");
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, settled_points, "
                + "arrears_points, create_time, update_time, deleted, active_flag) "
                + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'ACTIVE', '8888', 0, 0, 0, 0, 0, NOW(3), NOW(3), 0, 1)",
                orderId, TENANT, "WO-SO-" + suffix, siteId, cabinetId, slotId, 81619999L);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        // outbox 清全表（不带 tenant_id）：中继的 findDueIds 是**跳租户**的，
        // 而取件/取消路径在别的测试类里也会写 outbox 却不清——那些行会挤占 batchSize，
        // 让本用例自己那条排不进去。测试串行跑，全表清不会误伤谁。
        jdbc.update("delete from biz_outbox");
        jdbc.update("delete from biz_notice where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where id = (select site_id from biz_cabinet where id = ?)", cabinetId);
        jdbc.update("delete from biz_customer where id = 81619999");
        TenantContext.clear();
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    @Autowired
    private OutboxRelay outboxRelay;

    private void markDoorOpen() {
        TenantContext.runAs(TENANT, () -> states.markDoorOpenPastTolerance(slotId, 11));
        // 工单与消息现在由 outbox 中继投递（批 4）：标异常只写一行待投递事实。
        // 集成测试里调度是关掉的，所以夹具显式投递一次，让用例看到“扇出已完成”的现场。
        outboxRelay.deliverBatch();
    }

    @Test
    @DisplayName("同一格口允许 INCIDENT 与 REQUEST 共存；新字段写入且旧字段双写")
    void incidentAndRequestCanCoexist() {
        markDoorOpen();
        BizStorageOrder order = TenantContext.callAs(TENANT, () -> orderMapper.selectById(orderId));
        Long requestId = TenantContext.callAs(TENANT, () -> intake.onOrderEvent(
                order, WorkOrderType.DEVICE_FAULT, false,
                "AI 客服转报（用户自述，未经设备核实）：门打不开",
                "AI 客服转报：门打不开", LocalDateTime.now().plusMinutes(120),
                WorkOrderKind.REQUEST, WorkOrderSource.AI, WorkOrderIntent.DOOR_NOT_OPEN,
                WorkOrderCategory.DOOR_FAULT, WorkOrderPriority.P2,
                "{\"attempt\":\"retryMyDoor: 已下发无回执\"}", null));
        assertNotNull(requestId);

        List<Map<String, Object>> all = rows(
                "select id, kind, source, type, intent, category, priority, urgent, "
                        + "due_at, response_due_at, resolve_due_at, handoff_json "
                        + "from biz_work_order where tenant_id = ? and slot_id = ? order by create_time",
                TENANT, slotId);
        assertEquals(2, all.size(), "INCIDENT 与 REQUEST 必须能同格口同队列共存");

        Map<String, Object> request = all.stream()
                .filter(r -> "REQUEST".equals(r.get("kind")))
                .findFirst().orElseThrow();
        assertEquals("AI", request.get("source"));
        assertEquals("DOOR_NOT_OPEN", request.get("intent"));
        assertEquals("DOOR_FAULT", request.get("category"));
        assertEquals("P2", request.get("priority"));
        assertNotNull(request.get("due_at"), "旧 due_at 必须继续双写");
        assertNotNull(request.get("response_due_at"));
        assertNotNull(request.get("resolve_due_at"));
        assertTrue(String.valueOf(request.get("handoff_json")).contains("retryMyDoor"),
                "交接事实必须落库：" + request.get("handoff_json"));

        String actor = jdbc.queryForObject(
                "select actor_type from biz_work_order_event where work_order_id = ? and event_type = 'CREATED'",
                String.class, requestId);
        assertEquals("AI", actor, "AI 动作必须和人工/系统动作分开记");
    }

    @Test
    @DisplayName("AI 转报只给现象：系统分诊与设备巡检落到同一 category/queue；说不清进人工分拣")
    void aiReportIsTriagedBySystem() {
        markDoorOpen();
        BizStorageOrder order = TenantContext.callAs(TENANT, () -> orderMapper.selectById(orderId));
        Long aiId = TenantContext.callAs(TENANT, () -> intake.onOrderEvent(
                order, null, false, "AI 客服转报（用户自述）：门关不上",
                "AI 客服转报：门关不上", LocalDateTime.now().plusMinutes(120),
                WorkOrderKind.REQUEST, WorkOrderSource.AI, WorkOrderIntent.DOOR_NOT_CLOSE,
                null, null, null, null));
        Long unsortedId = TenantContext.callAs(TENANT, () -> intake.onOrderEvent(
                order, null, false, "AI 客服转报（用户自述）：说不清什么问题",
                "AI 客服转报：其他问题", LocalDateTime.now().plusMinutes(120),
                WorkOrderKind.REQUEST, WorkOrderSource.AI, WorkOrderIntent.OTHER,
                null, null, null, null));

        Map<String, Object> incident = jdbc.queryForMap(
                "select category, queue_side from biz_work_order where tenant_id = ? and kind = 'INCIDENT' and slot_id = ?",
                TENANT, slotId);
        Map<String, Object> ai = jdbc.queryForMap(
                "select type, category, queue_side from biz_work_order where id = ?", aiId);
        assertEquals(incident.get("category"), ai.get("category"),
                "AI 转报与设备巡检同一现象必须落到同一处置分类");
        assertEquals(incident.get("queue_side"), ai.get("queue_side"), "队列也必须是同一条");
        assertEquals("DOOR_NOT_CLOSED", ai.get("type"), "兼容 type 由系统按分类映射，AI 不再自己选");

        Map<String, Object> unsorted = jdbc.queryForMap(
                "select type, category, queue_side, assignee_group, priority from biz_work_order where id = ?",
                unsortedId);
        assertEquals("UNSORTED", unsorted.get("category"), "说不清的现象不硬派");
        assertEquals("CUSTOMER_SERVICE", unsorted.get("queue_side"));
        assertEquals("CUSTOMER_SERVICE", unsorted.get("assignee_group"));
        assertNull(unsorted.get("type"), "UNSORTED 没有兼容类型，同格口可并存多张待分拣请求");
    }

    @Test
    @DisplayName("门未关自动开一张工单；反复标同一异常不刷第二张，但会记下“又看到一次”")
    void anomalyMarksOpenExactlyOneWorkOrder() {
        markDoorOpen();
        markDoorOpen();

        List<Map<String, Object>> orders = rows(
                "select id, type, urgent, status, active_flag from biz_work_order where tenant_id = ? and slot_id = ?",
                TENANT, slotId);
        assertEquals(1, orders.size(), "同一格口的同一类异常在未关闭状态下必须只有一张工单");
        assertEquals("DOOR_NOT_CLOSED", orders.get(0).get("type"));
        assertEquals(WorkOrderStatus.PENDING.name(), orders.get(0).get("status"));

        Long id = ((Number) orders.get(0).get("id")).longValue();
        List<WorkOrderConsolePort.EventView> timeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(id));
        assertTrue(timeline.stream().anyMatch(e -> "CREATED".equals(e.eventType())), "开单必须留第一条痕");
        assertTrue(timeline.stream().anyMatch(e -> "REPEATED".equals(e.eventType())),
                "重复观察到也要留痕：它是“这格一直没关”的次数证据");

        // 快照里必须有业务员判断性质需要的东西（位置、编号、时间、电话、检测值）
        String snapshot = jdbc.queryForObject(
                "select cast(snapshot_json as char) from biz_work_order where id = ?", String.class, id);
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("13800001234"), "用户电话必须快照进来：" + snapshot);
        assertTrue(snapshot.contains(slotNo), "格口编号必须快照进来：" + snapshot);
        assertTrue(snapshot.contains("门=开着"), "检测值要能还原当时现场：" + snapshot);
    }

    @Test
    @DisplayName("柜内有物时门未关升为紧急异常；遗留物本身就是紧急")
    void urgentLevelFollowsDetection() {
        jdbc.update("update biz_compartment set presence = 'PRESENT' where id = ?", slotId);
        markDoorOpen();
        // TINYINT(1) 被驱动读成 Boolean，不是数字：拿 Integer 取会 TransientDataAccess/ClassCast
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select urgent from biz_work_order where tenant_id = ? and slot_id = ? and type = 'DOOR_NOT_CLOSED'",
                Boolean.class, TENANT, slotId), "有物的门未关就是紧急异常（用户口径）");

        TenantContext.runAs(TENANT, () -> states.markContentLeft(slotId, "结束后物检报有物"));
        outboxRelay.deliverBatch();
        List<Map<String, Object>> left = rows(
                "select type, urgent, snapshot_json from biz_work_order where tenant_id = ? and type = 'ITEM_LEFT'", TENANT);
        assertEquals(1, left.size());
        assertEquals(Boolean.TRUE, left.get(0).get("urgent"), "遗留物按紧急排队");
    }

    @Test
    @DisplayName("门关上：工单自动转化留底，不删除、不记成人工结论，也不再占唯一索引")
    void autoConvertKeepsRecordButFreesTheSlot() {
        markDoorOpen();
        Long id = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ?", Long.class, TENANT, slotId);

        TenantContext.runAs(TENANT, () -> states.tryAutoRecover(slotId,
                new Sensing(true, Presence.ABSENT, LocalDateTime.now())));

        Map<String, Object> row = rows("select status, active_flag, result_note, assignee_id from biz_work_order where id = ?", id).get(0);
        assertEquals(WorkOrderStatus.AUTO_CLOSED.name(), row.get("status"), "事实消失要自动转化，不能挂着让人误以为还要办");
        assertEquals(null, row.get("active_flag"), "关闭必须释放唯一索引槽位，否则这格下次异常开不出单");
        assertEquals(null, row.get("assignee_id"), "自动转化没有处理人：把机器动作记成人的功劳是假留痕");
        assertTrue(String.valueOf(row.get("result_note")).contains("自动转化"), "结论位要写清这是自动的：" + row.get("result_note"));
        assertFalse(rows("select id from biz_work_order where id = ?", id).isEmpty(), "用户要求留底：绝不删除");

        List<WorkOrderConsolePort.EventView> timeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(id));
        assertTrue(timeline.stream().anyMatch(e -> "AUTO_CLOSED".equals(e.eventType()) && e.operatorId() == null),
                "留痕里系统事件的 operator 必须为空");

        // 关掉之后，同一个格口再出同样的异常必须能开出新单
        markDoorOpen();
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ? and slot_id = ?", Integer.class, TENANT, slotId),
                "关闭后应能再开一张（历史一张、当前一张）");
    }

    @Test
    @DisplayName("认领只有一人成功；处理结论必填；派线下是另开子单而不是改父单")
    void claimThenResolveDispatchesChildOrder() {
        // 先让这单是紧急的（柜内有物 + 门未关），才能验证“子单继承级别”而不是“子单恒紧急”
        jdbc.update("update biz_compartment set presence = 'PRESENT' where id = ?", slotId);
        markDoorOpen();
        Long id = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ?", Long.class, TENANT, slotId);

        TenantContext.runAs(TENANT, () -> workOrders.claim(id, 777L));
        // 第二个人来认领：必须失败，不能静默把 assignee 换掉（那样两人都以为是自己办的）
        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> workOrders.claim(id, 888L)));
        assertTrue(rejected.getMessage().contains("认领"), rejected.getMessage());
        // 同一个人重放认领不该报错（前端重试、双击）
        TenantContext.runAs(TENANT, () -> workOrders.claim(id, 777L));

        assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> workOrders.resolve(id, 777L, "  ", false)),
                "结论空白必须拒：没有“谁说了什么”的工单没有价值");

        Long child = TenantContext.callAs(TENANT, () -> workOrders.resolve(id, 777L, "确认贵重物品滞留，已电话联系客户，需到场取出", true));
        assertNotNull(child, "需要线下时必须派出一张子单");
        assertEquals("FIELD", rows("select queue_side from biz_work_order where id = ?", child).get(0).get("queue_side"));
        assertEquals(id, ((Number) rows("select parent_id from biz_work_order where id = ?", child).get(0).get("parent_id")).longValue());
        assertEquals(Boolean.TRUE, rows("select urgent from biz_work_order where id = ?", child).get(0).get("urgent"),
                "线下子单继承紧急级别（用户：紧急异常需快速处理）");
        Map<String, Object> childRow = rows("select assignee_group, cast(handoff_json as char) as handoff_json "
                + "from biz_work_order where id = ?", child).get(0);
        assertEquals("FIELD_OPS", childRow.get("assignee_group"), "线下子单的责任组必须换成现场组");
        assertTrue(String.valueOf(childRow.get("handoff_json")).contains("需到场取出"),
                "子单交接包必须带父单结论：" + childRow.get("handoff_json"));
        assertEquals(WorkOrderStatus.RESOLVED.name(),
                rows("select status from biz_work_order where id = ?", id).get(0).get("status"));

        List<WorkOrderConsolePort.EventView> parentTimeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(id));
        assertTrue(parentTimeline.stream().anyMatch(e -> "CLAIMED".equals(e.eventType()) && 777L == e.operatorId()),
                "认领要能查到是谁");
        List<WorkOrderConsolePort.EventView> childTimeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(child));
        assertTrue(childTimeline.stream().anyMatch(e -> "DISPATCHED".equals(e.eventType())),
                "派发动作记在子单上：父单说“为什么派人”，子单说“谁派来的”");

        // 队列视图里线上只剩已关闭、线下多了一张待办（返回已改统一分页）
        PageResult<WorkOrderConsolePort.View> field = TenantContext.callAs(TENANT,
                () -> workOrders.queue(WorkOrderService.SIDE_FIELD, true, new PageQuery()));
        assertEquals(1, field.total());
        assertEquals(1, field.records().size());
        WorkOrderConsolePort.View fieldOrder = field.records().get(0);
        assertNotNull(fieldOrder.customerPhone(), "线下到场要能直接看到联系电话（快照，不回查）");
        assertEquals(Boolean.TRUE, fieldOrder.urgent(), "线下子单继承紧急级别，队列排序依赖它");
    }

    @Test
    @DisplayName("队列视图必须带回柜机编号/点位地址/检测值：业务员凭一行就知道去哪、看什么")
    void queueViewCarriesSnapshotFacts() {
        markDoorOpen();
        PageResult<WorkOrderConsolePort.View> online = TenantContext.callAs(TENANT,
                () -> workOrders.queue(WorkOrderService.SIDE_ONLINE, true, new PageQuery()));
        assertEquals(1, online.records().size());
        WorkOrderConsolePort.View v = online.records().get(0);
        assertNotNull(v.cabinetNo());
        assertTrue(v.cabinetNo().startsWith("CAB-WO-"), "柜机编号来自快照：" + v.cabinetNo());
        assertEquals("工单测试点位", v.siteAddress(), "点位地址来自快照（回查会被改名带跑）");
        assertNotNull(v.detection());
        assertTrue(v.detection().contains("门=开着"), "检测值要能还原现场：" + v.detection());
        assertEquals("13800001234", v.customerPhone());
    }

    @Test
    @DisplayName("同一现象：设备巡检与 AI 转报落到同一 category/queue")
    void deviceInspectionAndAiIntakeAgreeOnCategoryAndQueue() {
        markDoorOpen();

        Map<String, Object> device = rows(
                "select intent, category, queue_side from biz_work_order "
                        + "where tenant_id = ? and slot_id = ? and kind = 'INCIDENT'", TENANT, slotId).get(0);
        assertEquals(WorkOrderIntent.DOOR_NOT_CLOSE.name(), device.get("intent"),
                "巡检把“门开着”记成门未关，和用户在客服里说的是同一个现象");

        com.wherelee.cabinet.servicedesk.app.WorkOrderTriageService.Result ai =
                TenantContext.callAs(TENANT, () -> triage.triage(
                        WorkOrderIntent.DOOR_NOT_CLOSE,
                        com.wherelee.cabinet.servicedesk.app.WorkOrderTriageService.Facts.none()));

        assertEquals(device.get("category"), ai.category().name(),
                "同一现象不能因为来源不同（设备/客服）落到不同处置分类");
        assertEquals(device.get("queue_side"), ai.queueSide(), "责任队列也必须一致");
    }

    @Test
    @DisplayName("队列分页：3 张工单按 pageSize=2 翻页；紧急的排最前（兜底排序）")
    void queuePaginatesInsteadOfHardLimit() {
        markDoorOpen();
        TenantContext.runAs(TENANT, () -> states.markContentLeft(slotId, "结束后检出物品"));
        TenantContext.runAs(TENANT, () -> states.markSensorConflict(slotId, "门磁与物检矛盾"));
        outboxRelay.deliverBatch();

        PageQuery first = new PageQuery();
        first.setPageSize(2);
        PageResult<WorkOrderConsolePort.View> page1 = TenantContext.callAs(TENANT,
                () -> workOrders.queue(WorkOrderService.SIDE_ONLINE, true, first));
        assertEquals(3, page1.total());
        assertEquals(2, page1.pages());
        assertEquals(2, page1.records().size());
        assertEquals("ITEM_LEFT", page1.records().get(0).type(),
                "兜底排序：紧急在前（遗留物紧急，其余普通）");

        PageQuery second = new PageQuery();
        second.setPageNum(2);
        second.setPageSize(2);
        PageResult<WorkOrderConsolePort.View> page2 = TenantContext.callAs(TENANT,
                () -> workOrders.queue(WorkOrderService.SIDE_ONLINE, true, second));
        assertEquals(1, page2.records().size(), "第二页拿到剩下的 1 张，而不是被写死的 limit 200 丢掉");
    }

    @Test
    @DisplayName("四个端点各自挂权限点，且接口层不返回实体")
    void endpointsCarryTheirOwnPermissions() {
        var methods = AdminWorkOrderController.class.getDeclaredMethods();
        int guarded = 0;
        for (var m : methods) {
            var pre = m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
            if (pre != null) {
                guarded++;
                assertTrue(pre.value().contains("workorder:"), m.getName() + " 的权限点必须属于 workorder 族：" + pre.value());
            }
        }
        assertEquals(14, guarded,
                "十四个端点都要独立权限点（两列队列/时间线/证据/父子关系/AI 简报/认领/处理/派单/电话清单与回填/电话记录/刷新分诊/暂停与恢复SLA）；"
                        + "不能靠一个管理员点完，否则留痕里分不出责任");
        // 线下运维有 claim/resolve 却看不了单内容是自相矛盾：timeline/evidence/ai-brief 必须
        // 让两个队列的查看权都能过（field_ops 只有 field:list，缺了它认领后寸步难行；
        // AI 简报是给看单的人用的，同一口径，第 23 刀）
        for (String name : List.of("timeline", "evidence", "brief")) {
            var pre = java.util.Arrays.stream(methods)
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow()
                    .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
            assertTrue(pre.value().contains("workorder:online:list")
                            && pre.value().contains("workorder:field:list"),
                    name + " 必须同时放行线上与线下队列查看权：" + pre.value());
        }
        // 派线下是既有独立权限点（V17 已建），不能只校验 resolve 就放行
        var resolve = java.util.Arrays.stream(methods)
                .filter(m -> m.getName().equals("resolve")).findFirst().orElseThrow()
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertTrue(resolve.value().contains("workorder:dispatch-field"),
                "needOffline=true 必须额外要求派线下权限：" + resolve.value());
        // 接口层不得回传实体：拿泛型字符串直读，比反射拆 ParameterizedType 清楚得多
        String onlineReturn = java.util.Arrays.stream(methods)
                .filter(m -> m.getName().equals("online")).findFirst().orElseThrow()
                .getGenericReturnType().getTypeName();
        assertTrue(onlineReturn.contains("WorkOrderConsolePort$View"),
                "队列必须返回契约视图（api）而不是实体：" + onlineReturn);
        assertFalse(onlineReturn.contains("BizWorkOrder"), "实体不得出现在接口层返回值里：" + onlineReturn);
    }

    @Test
    @DisplayName("无关订单不阻塞工单：空格的传感器矛盾没有物主也要能开单")
    void anomalyWithoutOrderStillOpensWorkOrder() {
        jdbc.update("update biz_compartment set current_order_id = null, status = 'FREE' where id = ?", slotId);
        TenantContext.runAs(TENANT, () -> states.markSensorConflict(slotId, "门磁与物检矛盾"));
        outboxRelay.deliverBatch();

        List<Map<String, Object>> list = rows(
                "select type, urgent, order_no, customer_id from biz_work_order where tenant_id = ? and slot_id = ?",
                TENANT, slotId);
        assertEquals(1, list.size());
        assertEquals("DEVICE_FAULT", list.get(0).get("type"));
        assertEquals(Boolean.FALSE, list.get(0).get("urgent"), "传感器矛盾按普通排队：它不影响钱");
        assertEquals(null, list.get(0).get("order_no"), "没有一个活着的单时，工单只指向格口");
    }

    @Test
    @DisplayName("扇出失败不回滚事实：工单投不出去，异常态与流水照样在")
    void fanOutFailureDoesNotRollBackTheFact() {
        // 改造前这里根本走不到：同步调工单时下游一抛异常，标异常与写流水全部回滚，
        // 用户的操作（上报遗留物/取件）也跟着失败。现在业务事实先提交，派生动作落后一步。
        TenantContext.runAs(TENANT, () -> states.markDoorOpenPastTolerance(slotId, 11));

        assertEquals("DOOR_OPEN", jdbc.queryForObject(
                        "select anomaly from biz_compartment where id = ?", String.class, slotId),
                "异常态必须已经提交，与工单投递成败无关");
        assertEquals(1, rows("select id from biz_fault_event where slot_id = ?", slotId).size(),
                "故障流水必须已经提交");

        // 把载荷弄坏，模拟“工单侧投递失败”
        jdbc.update("update biz_outbox set payload = '{broken' where tenant_id = ? and status = 'PENDING'", TENANT);
        assertEquals(0, outboxRelay.deliverBatch(), "载荷坏掉时投不出去");

        Map<String, Object> failed = jdbc.queryForMap(
                "select status, attempt, last_error from biz_outbox where tenant_id = ? order by id desc limit 1",
                TENANT);
        assertEquals("FAILED", String.valueOf(failed.get("status")), "投递失败要进退避，不能丢");
        assertEquals(1L, ((Number) failed.get("attempt")).longValue());
        assertNotNull(failed.get("last_error"), "失败原因要留下来，否则没人知道为什么没投出去");

        // 判死：把 attempt 顶到上限的前一次，下一轮失败就该 DEAD 等人工
        jdbc.update("update biz_outbox set attempt = 4, next_attempt_at = now(3) where tenant_id = ?", TENANT);
        outboxRelay.deliverBatch();
        assertEquals("DEAD", jdbc.queryForObject(
                        "select status from biz_outbox where tenant_id = ? order by id desc limit 1", String.class, TENANT),
                "重试预算用尽要判死等人工，不能无限重试（与延迟任务同一个上限）");
    }

    @Test
    @DisplayName("升到“必须处理”时同时产出：业务员一条电话任务 + 客户一条站内消息，且不得重复发")
    void escalationEmitsCallTaskAndCustomerMessage() {
        markDoorOpen();
        Long wo = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ?", Long.class, TENANT, slotId);

        assertTrue(TenantContext.callAs(TENANT,
                () -> workOrders.escalate(slotId, WorkOrderType.DOOR_NOT_CLOSED, "门未关满 10 分钟，需业务员处理")),
                "升级应当生效（单还开着且未升级过）");

        List<Map<String, Object>> calls = rows(
                "select channel, content, customer_id, done_at from biz_notice where work_order_id = ? and channel = 'PHONE_CALL'", wo);
        assertEquals(1, calls.size(), "升级要产出一条“必须打电话”的待办：没有它，“打电话提醒”只是一句口说法");
        assertTrue(String.valueOf(calls.get(0).get("content")).contains("13800001234"),
                "任务里必须带客户电话，不能让业务员再去别处查：" + calls.get(0).get("content"));
        assertEquals(null, calls.get(0).get("done_at"), "没回填就是还欠着，不能一生成就算办完");
        assertNotNull(calls.get(0).get("customer_id"), "电话任务也要记下是谁的事，否则无法回溯联系对象");

        assertEquals(1, rows(
                "select id from biz_notice where work_order_id = ? and channel = 'SYSTEM_MESSAGE'", wo).size(),
                "同时给客户一条站内消息（没有推送，他自己看到也算提醒到）");

        // 阶梯每轮都会重进升级分支：第二次必须什公都不产生
        assertFalse(TenantContext.callAs(TENANT,
                () -> workOrders.escalate(slotId, WorkOrderType.DOOR_NOT_CLOSED, "重复升级")),
                "已升级过的单不应再命中一次升级");
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from biz_notice where work_order_id = ?", Integer.class, wo),
                "升级只产出一次：一条电话任务 + 一条站内消息");
    }

    @Test
    @DisplayName("客户只能标自己收到的消息已读；电话结果必填且不得重复回填")
    void readAndCallDoneGuards() {
        markDoorOpen();
        TenantContext.runAs(TENANT, () -> workOrders.escalate(slotId, WorkOrderType.DOOR_NOT_CLOSED, "到点"));
        // 裸 SQL 必须带租户：同一个 JVM 里别的用例类也在写 biz_notice，
        // 不带条件的 limit 1 会捞到他们的消息，症状是“标已读报了别人的账号”这种假错
        Long messageId = jdbc.queryForObject(
                "select id from biz_notice where tenant_id = ? and channel = 'SYSTEM_MESSAGE' order by id desc limit 1",
                Long.class, TENANT);
        Long callId = jdbc.queryForObject(
                "select id from biz_notice where tenant_id = ? and channel = 'PHONE_CALL' order by id desc limit 1",
                Long.class, TENANT);

        // 别人来读：条件里带 customer_id，改不到别人的消息
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> notices.markRead(messageId, 999999L)), "不得替别人标已读");
        TenantContext.runAs(TENANT, () -> notices.markRead(messageId, 81619999L));
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> notices.markRead(messageId, 81619999L)), "重复已读必须失败而不是静默成功");

        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> notices.markCallDone(callId, "  ")), "不填结果就想销掉电话任务，不行");
        TenantContext.runAs(TENANT, () -> notices.markCallDone(callId, "已联系，客户十分钟内回来关门"));
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> notices.markCallDone(callId, "又被回填一次")), "第二个人不得盖掉第一个人的结论");
        // 回填后不再出现在待打电话清单里
        assertTrue(TenantContext.callAs(TENANT, () -> notices.pendingCalls().stream().noneMatch(v -> v.id().equals(callId))),
                "已回填的任务必须从清单消失，否则队列永远处理不完");
    }

    @Test
    @DisplayName("系统自动派线下：子单进线下队列，线上父单不得被标成“人已处理”")
    void autoFieldDispatchKeepsParentOpen() {
        markDoorOpen();
        Long doorWo = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ?", Long.class, TENANT, slotId);
        // 把一张“超 48 小时”类型的单建出来（直接用阶梯同样的入口链），再用它的 id 测自动派单
        TenantContext.runAs(TENANT, () -> intake.longRunningFor(slotId, "超 48 小时未结束"));
        Long parent = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and type = 'LONG_RUNNING' and queue_side = 'ONLINE'",
                Long.class, TENANT);
        assertNotNull(parent, "到顶时应自动开一张线上 LONG_RUNNING 单");

        Long child = TenantContext.callAs(TENANT, () -> workOrders.openFieldTwin(parent, "超 72 小时未取件"));
        assertNotNull(child);
        assertEquals("FIELD", rows("select queue_side from biz_work_order where id = ?", child).get(0).get("queue_side"));
        assertEquals(parent, ((Number) rows("select parent_id from biz_work_order where id = ?", child)
                .get(0).get("parent_id")).longValue());
        assertEquals(WorkOrderStatus.PENDING.name(),
                rows("select status from biz_work_order where id = ?", parent).get(0).get("status"),
                "父单不能被机器标成已处理：那等于把“有人做过判断”伪造出来");
        // 幂等：每小时都会重进这里，不得堆出第二张线下单
        Long again = TenantContext.callAs(TENANT, () -> workOrders.openFieldTwin(parent, "重试"));
        assertEquals(child, again, "同一父单只能有一张未关闭的线下子单");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from biz_work_order where parent_id = ?", Integer.class, parent).intValue(),
                "子单不得因重进而翻倍");
        assertNotNull(doorWo);
    }

    @Test
    @DisplayName("线上父单与线下子单共存：提醒/升级/阶梯查询不得因 selectOne 多条而中断")
    void remindAndEscalateTolerateParentAndChildCoexistence() {
        markDoorOpen();
        Long parent = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ? and queue_side = 'ONLINE'",
                Long.class, TENANT, slotId);
        Long child = TenantContext.callAs(TENANT, () -> workOrders.openFieldTwin(parent, "用例：父子共存"));
        assertEquals("FIELD", rows("select queue_side from biz_work_order where id = ?", child).get(0).get("queue_side"));

        // V18 允许父子单同格口同类型共存。findOpen 若不区分 queue_side，selectOne 会命中两条直接抛异常，
        // 症状是门未关阶梯每轮巡检都在“提醒”这一步断掉。
        // markDoorOpen 本身已走过一轮阶梯提醒，这里验的是“父子共存后还能继续催”，不是绝对次数
        int remindersBefore = TenantContext.callAs(TENANT,
                () -> workOrders.remindedTimes(slotId, WorkOrderType.DOOR_NOT_CLOSED));
        assertTrue(TenantContext.callAs(TENANT, () -> workOrders.remind(
                slotId, WorkOrderType.DOOR_NOT_CLOSED, "再催一次")));
        assertEquals(remindersBefore + 1, TenantContext.callAs(TENANT,
                () -> workOrders.remindedTimes(slotId, WorkOrderType.DOOR_NOT_CLOSED)));
        assertEquals(parent, TenantContext.callAs(TENANT,
                () -> workOrders.openIdOf(slotId, WorkOrderType.DOOR_NOT_CLOSED)),
                "阶梯必须指向线上父单，不能被线下子单顶替");
        assertNotNull(TenantContext.callAs(TENANT, () -> workOrders.boundOrderId(slotId)),
                "绑定单查询也不能因父子共存而断");

        assertTrue(TenantContext.callAs(TENANT,
                () -> workOrders.escalate(slotId, WorkOrderType.DOOR_NOT_CLOSED, "到点")));
        List<WorkOrderConsolePort.EventView> parentTimeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(parent));
        assertTrue(parentTimeline.stream().anyMatch(e -> "ESCALATED".equals(e.eventType())),
                "升级事件记在线上父单上");
        List<WorkOrderConsolePort.EventView> childTimeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(child));
        assertTrue(childTimeline.stream().noneMatch(e -> "ESCALATED".equals(e.eventType())),
                "线下子单不该被重复记一次升级事件");
    }

    @Test
    @DisplayName("自动派过线下单后再点“需线下”：复用子单，不撞唯一索引、不回滚父单结论")
    void resolveAfterAutoDispatchReusesExistingFieldChild() {
        markDoorOpen();
        Long parent = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ? and queue_side = 'ONLINE'",
                Long.class, TENANT, slotId);
        Long autoChild = TenantContext.callAs(TENANT, () -> workOrders.openFieldTwin(parent, "用例：超时自动派单"));

        Long resolvedChild = TenantContext.callAs(TENANT,
                () -> workOrders.resolve(parent, 777L, "确认需到场处理", true));
        assertEquals(autoChild, resolvedChild, "已存在线下子单时必须复用，不能另插一张撞唯一索引");
        assertEquals(WorkOrderStatus.RESOLVED.name(),
                rows("select status from biz_work_order where id = ?", parent).get(0).get("status"),
                "父单结论必须落库：子单冲突不得把整笔处理回滚");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from biz_work_order where parent_id = ?", Integer.class, parent).intValue(),
                "同一父单只能有一张线下子单");
        List<WorkOrderConsolePort.EventView> childTimeline = TenantContext.callAs(TENANT, () -> workOrders.timeline(autoChild));
        assertTrue(childTimeline.stream().anyMatch(e -> "DISPATCHED".equals(e.eventType())),
                "复用也要留痕：让后来的人知道这张子单不是这次点击新开的");
    }

    @Test
    @DisplayName("代关：只受理“门未关”的格口，其余异常不给外人“关一下就好”")
    void helperCloseRefusesStrongerLocks() {
        // 遗留物是强锁：外人关一下不得把它降级成正常格口
        TenantContext.runAs(TENANT, () -> states.markContentLeft(slotId, "结束后检出物品"));
        BizException refused = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> states.confirmClosedByAnother(slotId, 81619999L)));
        assertTrue(refused.getMessage().contains("门未关"), refused.getMessage());
        assertEquals(CompartmentAnomaly.CONTENT_LEFT.name(), jdbc.queryForObject(
                "select anomaly from biz_compartment where id = ?", String.class, slotId),
                "强锁必须还在：否则一推门就把有件的格子放回可售池");

        // 门未关的格口才会出现在“只差关门”的清单里
        TenantContext.runAs(TENANT, () -> states.markDoorOpenPastTolerance(slotId, 4));
        assertEquals(1, TenantContext.callAs(TENANT, () -> states.closableSlots(cabinetId)).size(),
                "可代关清单只能包含「门未关」这一类");
    }

    @Test
    @DisplayName("证据采集：看管检测留一条带三值结论的记录，能从工单上读到；虚拟设备下不存图片引用")
    void evidenceCollectedAndReadableFromWorkOrder() {
        markDoorOpen();
        Long wo = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ?", Long.class, TENANT, slotId);

        List<WorkOrderConsolePort.EvidenceView> evidence =
                TenantContext.callAs(TENANT, () -> workOrders.evidenceFor(wo));
        assertTrue(evidence.stream().anyMatch(e -> "WATCH_DETECT".equals(e.moment())),
                "门未关阶梯上的检测必须留证据，否则业务员事后不知道当时看到什么：" + evidence);
        assertTrue(evidence.stream().allMatch(e -> e.presence() != null),
                "三值结论不得为空：测不准也要写 UNKNOWN，不能留空让人当“没东西”读");
        assertTrue(evidence.stream().allMatch(e -> e.photoRef() == null),
                "本项目是虚拟设备，不得存一个不存在的图片引用");
    }
}

package com.wherelee.cabinet.storage;

import com.wherelee.cabinet.locker.app.LockerConsoleService;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.storage.app.LeftoverRetrieveService;
import com.wherelee.cabinet.storage.app.StorageQueryService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.device.infra.SimulatedCabinetChannel;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderEventMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 21 刀遗留物取回闭环：发现即通知物主 → 物主自助开门 → 复检收口自动回可售。
 *
 * <p>这条链上的安全底线各有一道专设断言：通知一张工单只发一条（重复观察不打扰）；
 * 开门三重校验（归属/格口状态/占用）；收口只认"物主本人 + 复检无物"双凭据；
 * 运维清柜前必须拿到"柜内已空"凭据，否则就是把别人的东西卖给下一位。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("遗留物取回闭环：一张遗留物从被发现到被拿走")
class LeftoverRetrieveFlowTest {

    private static final Long TENANT = 8162L;
    private static final Long OWNER = 81620001L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private CompartmentStateService states;
    @Autowired
    private LeftoverRetrieveService retrievals;
    @Autowired
    private LockerConsoleService console;
    @Autowired
    private StorageQueryService queries;
    @Autowired
    private WorkOrderService workOrders;
    @Autowired
    private SimulatedCabinetChannel simulator;
    @Autowired
    private BizFaultEventMapper faultMapper;
    @Autowired
    private BizWorkOrderEventMapper eventMapper;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private Long slotId;
    private Long orderId;
    private String orderNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // 主键区间随机避让（共享测试库，同 WorkOrderFlowTest 的教训：桶太少跨类必撞）
        Long siteId = java.util.concurrent.ThreadLocalRandom.current().nextLong(82_6100_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        orderId = siteId + 3;
        orderNo = "LO-SO-" + suffix;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '遗留物测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "LR-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, '遗留物测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-LR-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'SMALL', 'FREE', NULL, NULL, NULL, NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId, "L" + suffix.substring(0, 4));
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                + "values (?, ?, ?, '遗留物物主', '13900005678', 1, NOW(3), NOW(3), NOW(3), 0)",
                OWNER, TENANT, "lr-" + suffix);
        // 物主那张单已经是终态：遗留物被发现时活动单早已结束，这正是取回闭环要服务的现场
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, settled_points, "
                + "arrears_points, finished_at, create_time, update_time, deleted, active_flag) "
                + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'CLOSED', ?, 0, 0, 0, 0, 0, NOW(3), NOW(3), NOW(3), 0, NULL)",
                orderId, TENANT, orderNo, siteId, cabinetId, slotId, OWNER, "V" + suffix.substring(0, 2) + siteId % 1000000);
    }

    @AfterEach
    void cleanup() {
        // outbox 清全表：中继跳租户取待投行，其它测试类积压的行会挤占 batchSize
        jdbc.update("delete from biz_outbox");
        jdbc.update("delete from biz_notice where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_item_evidence where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_customer where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
        TenantContextClear();
    }

    private void TenantContextClear() {
        com.wherelee.cabinet.common.context.TenantContext.clear();
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    @Autowired
    private OutboxRelay outboxRelay;

    private void markLeftover() {
        com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                () -> states.markContentLeft(slotId, "取物关门后复检到物品"));
        // 工单与物主通知现在由 outbox 中继投递（批 4）：标异常只写一行待投递事实，
        // 集成测试里调度是关掉的，所以夹具显式投递一次
        outboxRelay.deliverBatch();
    }

    @Test
    @DisplayName("发现遗留物即通知物主：一张未关闭工单只发一条，订单被打上待取回标记")
    void ownerNotifiedOncePerOpenWorkOrder() {
        markLeftover();
        markLeftover(); // 重复观察：工单合并到同一张，通知不得重复打扰物主

        List<Map<String, Object>> notices = rows(
                "select content, customer_id, dedup_key, order_no from biz_notice "
                        + "where tenant_id = ? and kind = 'LEFTOVER_OWNER'", TENANT);
        assertEquals(1, notices.size(), "同一张未关闭工单只给物主发一条站内消息");
        assertEquals(OWNER, ((Number) notices.get(0).get("customer_id")).longValue());
        assertTrue(String.valueOf(notices.get(0).get("content")).contains("取回"),
                "文案要告诉物主怎么取回：" + notices.get(0).get("content"));

        String markedAt = jdbc.queryForObject(
                "select leftover_reported_at from biz_storage_order where id = ?", String.class, orderId);
        assertNotNull(markedAt, "物主的终态单要打上被上报标记");

        assertEquals(1, rows("select id from biz_work_order where tenant_id = ? and type = 'ITEM_LEFT' "
                + "and queue_side = 'ONLINE'", TENANT).size(), "遗留物照旧自动开一张线上工单");
    }

    @Test
    @DisplayName("物主自助取回：开门不改单不启动计费；复检无物后格口回可售并全程留痕")
    void openTakeAndSlotBackOnSale() {
        markLeftover();

        StorageQueryService.OrderView before = com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> queries.myOrders(OWNER, 20)).stream()
                .filter(v -> v.orderNo().equals(orderNo)).findFirst().orElseThrow();
        assertTrue(before.leftoverRetrievable(), "列表要亮出“待取回”，点进去才不会扑空");

        String openReply = com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> retrievals.openForRetrieval(OWNER, orderNo));
        assertTrue(openReply.contains("柜门已打开"), openReply);

        // 取回不动订单：终态就是终态，计费起点不得被补写
        Map<String, Object> order = rows("select status, started_at from biz_storage_order where id = ?", orderId).get(0);
        assertEquals("CLOSED", order.get("status"));
        assertNull(order.get("started_at"), "取回开门不落计费起点：这不是一笔新业务");
        Map<String, Object> slot = rows("select door_open_at, anomaly from biz_compartment where id = ?", slotId).get(0);
        assertNotNull(slot.get("door_open_at"), "门态要记下（开着期间本就不可分配）");
        assertEquals("CONTENT_LEFT", slot.get("anomaly"), "取回开门不解除强锁：东西没拿走前格子不能卖");

        simulator.simulateDoor(cabinetId, slotId, true); // 物主取走后把门关上
        String takenReply = com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> retrievals.confirmRetrieved(OWNER, orderNo));
        assertTrue(takenReply.contains("已确认取完"), takenReply);

        assertNull(rows("select anomaly from biz_compartment where id = ?", slotId).get(0).get("anomaly"),
                "复检无物后格口自动回可售（用户定的收口方式）");
        assertEquals(1, com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> faultMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.wherelee.cabinet.device.domain.BizFaultEvent>lambdaQuery()
                        .eq(com.wherelee.cabinet.device.domain.BizFaultEvent::getSlotId, slotId)
                        .eq(com.wherelee.cabinet.device.domain.BizFaultEvent::getAction,
                                com.wherelee.cabinet.device.api.FaultType.Action.RECOVER))).size(),
                "解除动作要进台账：谁取的、复检结果是什么");
        assertEquals(1, com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> eventMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent>lambdaQuery()
                        .eq(com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent::getEventType,
                                "RETRIEVED_BY_OWNER"))).size(),
                "工单流水要记一笔物主取回：业务员回填结论时看得到");
        assertTrue(com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> queries.myOrders(OWNER, 20)).stream().filter(v -> v.orderNo().equals(orderNo))
                        .findFirst().orElseThrow().leftoverRetrievable() == false,
                "取完之后列表不再亮待取回");
    }

    @Test
    @DisplayName("开门三重校验：不是自己的单不可见；进行中的单不给走取回；格口非遗留物状态不开")
    void retrievalGuards() {
        markLeftover();

        BizException foreign = assertThrows(BizException.class,
                () -> com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                        () -> retrievals.openForRetrieval(99999999L, orderNo)),
                "别人的单一律“不存在”，不区分归属与缺失");
        assertTrue(foreign.getMessage().contains("不存在"), foreign.getMessage());

        // 进行中的单：终态校验先于格口检查
        Long activeSlot = slotId + 10;
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                        + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, 'SMALL', 'OCCUPIED', NULL, NULL, NULL, NOW(3), NOW(3), 0)",
                activeSlot, TENANT, cabinetId, "LX" + activeSlot.toString().substring(activeSlot.toString().length() - 3));
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                        + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, settled_points, "
                        + "arrears_points, create_time, update_time, deleted, active_flag) "
                        + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'ACTIVE', 'V87654321', 0, 0, 0, 0, 0, NOW(3), NOW(3), 0, 1)",
                orderId + 10, TENANT, orderNo + "X", cabinetId - 1, cabinetId, activeSlot, OWNER);
        BizException notTerminal = assertThrows(BizException.class,
                () -> com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                        () -> retrievals.openForRetrieval(OWNER, orderNo + "X")));
        assertTrue(notTerminal.getMessage().contains("还没结束"), notTerminal.getMessage());

        // 格口异常已被别人处理（清柜/自动恢复）：没有遗留物可取
        jdbc.update("update biz_compartment set anomaly = null where id = ?", slotId);
        BizException nothing = assertThrows(BizException.class,
                () -> com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                        () -> retrievals.openForRetrieval(OWNER, orderNo)));
        assertTrue(nothing.getMessage().contains("没有待取回"), nothing.getMessage());
    }

    @Test
    @DisplayName("清柜安全闸：柜内有物拒绝解除；确认已空才放行")
    void consoleResolveBlockedWhileItemInside() {
        markLeftover();
        simulator.simulateItemInside(cabinetId, slotId, true);

        BizException blocked = assertThrows(BizException.class,
                () -> com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                        () -> console.resolveAnomaly(slotId, "现场已看过，关门处理")));
        assertTrue(blocked.getMessage().contains("不能解除异常"), blocked.getMessage());
        assertEquals("CONTENT_LEFT", rows("select anomaly from biz_compartment where id = ?", slotId)
                .get(0).get("anomaly"), "安全闸拒绝后强锁必须还在");

        simulator.simulateItemInside(cabinetId, slotId, false);
        com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                () -> console.resolveAnomaly(slotId, "现场柜内为空，已确认"));
        assertNull(rows("select anomaly from biz_compartment where id = ?", slotId).get(0).get("anomaly"),
                "确认已空后清柜照常生效");
    }

    @Test
    @DisplayName("业务员可选电话记录：打了才留痕，结果必填")
    void phoneNoteRecorded() {
        markLeftover();
        Long workOrderId = jdbc.queryForObject(
                "select id from biz_work_order where tenant_id = ? and slot_id = ? and type = ?",
                Long.class, TENANT, slotId, WorkOrderType.ITEM_LEFT.name());

        BizException blank = assertThrows(BizException.class,
                () -> com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                        () -> workOrders.notePhoneCall(workOrderId, 777L, "  ")));
        assertTrue(blank.getMessage().contains("联系结果必填"), blank.getMessage());

        com.wherelee.cabinet.common.context.TenantContext.runAs(TENANT,
                () -> workOrders.notePhoneCall(workOrderId, 777L, "已联系物主，约定明天上午来柜取回"));
        assertEquals(1, com.wherelee.cabinet.common.context.TenantContext.callAs(TENANT,
                () -> eventMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent>lambdaQuery()
                        .eq(com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent::getWorkOrderId, workOrderId)
                        .eq(com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent::getEventType, "PHONE_CALLED"))).size(),
                "电话记录进事件流水：谁打的、结果是什么");
    }
}

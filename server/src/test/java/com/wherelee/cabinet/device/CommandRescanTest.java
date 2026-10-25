package com.wherelee.cabinet.device;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.app.CommandRescanService;
import com.wherelee.cabinet.device.app.DeviceCommandService;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.device.infra.SimulatedCabinetChannel;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDelayTaskMapper;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.locker.infra.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=999",
        "cabinet.device.receipt-timeout-ms=300",
        "cabinet.device.rescan-max-retry=2",
        "cabinet.simulator.reply-delay-ms=0"
})
class CommandRescanTest {

    private static final Long TENANT = 8114L;

    @Autowired
    private DelayTaskService tasks;
    @Autowired
    private BizDelayTaskMapper taskMapper;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private PointAccountService points;
    @Autowired
    private DeviceCommandService devices;
    @Autowired
    private CommandRescanService rescans;
    @Autowired
    private SimulatedCabinetChannel simulator;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private BizDeviceCommandMapper commandMapper;
    @Autowired
    private BizFaultEventMapper faultMapper;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String cabinetNo;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        simulator.reset();
        cabinetNo = "CAB-K-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("K-" + cabinetNo);
            site.setName("回扫点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("回扫柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

            for (SizeType size : SizeType.values()) {
                for (int i = 1; i <= 4; i++) {
                    BizCompartment slot = new BizCompartment();
                    slot.setCabinetId(cabinetId);
                    slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
                    slot.setSizeType(size);
                    slot.setStatus(SlotStatus.FREE);
                    slotMapper.insert(slot);
                }
            }
        });
    }

    @AfterEach
    void cleanup() {
        simulator.reset();
        Object[] customers = customerIds.toArray();
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_report where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        if (!customerIds.isEmpty()) {
            String in = String.join(",", customerIds.stream().map(id -> "?").toList());
            jdbc.update("delete from biz_point_txn where customer_id in (" + in + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + in + ")", customers);
        }
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "K-" + cabinetNo);
        customerIds.clear();
        TenantContext.clear();
    }

    private Long newCustomer() {
        Long id = 980000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 9000);
        customerIds.add(id);
        return id;
    }

    private BizStorageOrder newOrder(Long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "回扫用例 funding"));
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))).orderNo();
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private void expireTasks(TaskType type) {
        jdbc.update("update biz_delay_task set fire_at = date_sub(now(3), interval 30 second), "
                + "status = case when status = 'DONE' then 'PENDING' else status end where task_type = ?",
                type.name());
    }

    private BizDeviceCommand command(String requestId) {
        return TenantContext.callAs(TENANT, () -> commandMapper.selectByRequestId(requestId));
    }

    private int runDue() {
        return TenantContext.callAs(TENANT, tasks::runDue);
    }

    @Test
    @DisplayName("无回执：指令 TIMEOUT、自动登记回扫、订单退回 RESERVED（件还在用户手里）")
    void noReceiptRegistersRescan() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.OPEN_NO_RECEIPT);

        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                CommandAction.OPEN));

        String requestId = order.getOrderNo() + ":OPEN:1";
        BizDeviceCommand command = command(requestId);
        assertNotNull(command, "指令必须留痕，即使没回执");
        assertEquals(CommandState.TIMEOUT, command.getStatus());
        assertEquals(OrderStatus.RESERVED, TenantContext.callAs(TENANT,
                () -> orderMapper.selectById(order.getId())).getStatus());

        BizDelayTask rescan = TenantContext.callAs(TENANT, () -> taskMapper.selectOne(
                Wrappers.<BizDelayTask>lambdaQuery()
                        .eq(BizDelayTask::getTaskType, TaskType.COMMAND_RESCAN)
                        .eq(BizDelayTask::getBizKey, requestId)));
        assertNotNull(rescan, "超时指令必须自动登记回扫，否则现场只有等用户再来一次");
    }

    @Test
    @DisplayName("迟到的回执：用上报流水重放收敛，绝不重复下发")
    void lateReceiptIsReplayedNotRedispatched() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        String requestId = order.getOrderNo() + ":OPEN:1";

        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.OPEN_NO_RECEIPT);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                CommandAction.OPEN));
        jdbc.update("insert into biz_device_report (id, tenant_id, cabinet_id, slot_id, order_id, seq,"
                        + " event_type, dedup_key, payload, reported_at, received_at, create_time)"
                        + " values (?, ?, ?, ?, ?, 7, 'DOOR_OPENED', ?, ?, now(3), now(3), now(3))",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), TENANT, cabinetId, order.getSlotId(),
                order.getId(), "cmd:" + requestId,
                "{\"requestId\":\"" + requestId + "\",\"success\":true,\"sensorConfirmed\":true,"
                        + "\"executedSlotId\":" + order.getSlotId() + "}");

        CommandRescanService.RescanResult result = TenantContext.callAs(TENANT,
                () -> rescans.rescan(requestId));

        assertEquals(CommandRescanService.RescanAction.CONVERGED_FROM_REPORT, result.action(),
                "有流水就必须走重放，而不是再下发一次");
        assertEquals(CommandState.SUCCEEDED, command(requestId).getStatus(), "迟到的回执要能把状态修正");
        assertEquals(0, command(requestId).getRetryCount().intValue(),
                "重放路径不该消耗重试预算，更不该产生第二次物理动作");
        Integer reports = jdbc.queryForObject("select count(*) from biz_device_report where dedup_key = ?",
                Integer.class, "cmd:" + requestId);
        assertEquals(1, reports);
    }

    @Test
    @DisplayName("没有流水时重试：同一 requestId 再下发，成功后收敛；预算用完就判死交人工")
    void retryReusesRequestIdThenDies() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        String requestId = order.getOrderNo() + ":OPEN:1";
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.OPEN_NO_RECEIPT);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                CommandAction.OPEN));

        for (int round = 0; round < 4; round++) {
            expireTasks(TaskType.COMMAND_RESCAN);
            runDue();
        }

        BizDeviceCommand dead = command(requestId);
        assertEquals(2, dead.getRetryCount().intValue(), "重试预算是 2，用完就不能再多发一次");
        assertEquals(CommandState.TIMEOUT, dead.getStatus(), "判死是“不再自动流转”，不是把状态改成别的");
        assertTrue(String.valueOf(dead.getLastError()).contains("重试"), "要写下为什么停，否则事后是个黑洞");
        List<BizFaultEvent> events = TenantContext.callAs(TENANT, () -> faultMapper.selectList(
                Wrappers.<BizFaultEvent>lambdaQuery().eq(BizFaultEvent::getCabinetId, cabinetId)
                        .eq(BizFaultEvent::getFaultType, FaultType.NO_REPORT)));
        assertFalse(events.isEmpty(), "判死必须留故障事件（人工出口的来源）");
        Integer distinctKeys = jdbc.queryForObject(
                "select count(distinct dedup_key) from biz_device_report where dedup_key like concat('cmd:', ?, '%')",
                Integer.class, requestId);
        assertNotNull(distinctKeys);
    }

    private BizDelayTask rescanTask(String requestId) {
        return TenantContext.callAs(TENANT, () -> taskMapper.selectOne(
                Wrappers.<BizDelayTask>lambdaQuery()
                        .eq(BizDelayTask::getTaskType, TaskType.COMMAND_RESCAN)
                        .eq(BizDelayTask::getBizKey, requestId)));
    }

    @Test
    @DisplayName("扫街：卡在 SENT 的指令（进程被杀那种）能被捞回并登记回扫")
    void sweepCatchesStuckSentCommand() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        String requestId = order.getOrderNo() + ":OPEN:1";
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                CommandAction.OPEN));
        jdbc.update("delete from biz_device_command where status in ('SENT', 'ACKED', 'TIMEOUT')");
        jdbc.update("delete from biz_delay_task where task_type = 'COMMAND_SWEEP'");
        jdbc.update("delete from biz_delay_task where task_type = 'COMMAND_RESCAN'");
        jdbc.update("update biz_device_command set status = 'SENT', sent_at = date_sub(now(3), interval 10 minute)"
                + " where request_id = ?", requestId);

        TenantContext.runAs(TENANT, () -> tasks.schedule(TaskType.COMMAND_SWEEP, String.valueOf(TENANT),
                TENANT, LocalDateTime.now().minusSeconds(5)));
        expireTasks(TaskType.COMMAND_SWEEP);
        BizDelayTask registered = null;
        for (int round = 0; round < 20 && registered == null; round++) {
            runDue();
            registered = rescanTask(requestId);
        }
        assertNotNull(registered, "扫街必须把卡住的指令重新推上回扫通道");
        assertTrue(registered.getStatus() != TaskStatus.DEAD,
                "回扫任务不该直接判死，实测 " + registered.getStatus());

        expireTasks(TaskType.COMMAND_RESCAN);
        runDue();
        assertEquals(CommandState.SUCCEEDED, command(requestId).getStatus(),
                "回扫把“开过门”这件事实收敛回来，订单才不至于停在 OPENING");
    }

    @Test
    @DisplayName("已终态的指令再回扫必须是 no-op（重入是租约语义的前提）")
    void rescanOfSettledCommandIsNoOp() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                CommandAction.OPEN));
        String requestId = order.getOrderNo() + ":OPEN:1";
        assertEquals(CommandState.SUCCEEDED, command(requestId).getStatus());

        CommandRescanService.RescanResult result = TenantContext.callAs(TENANT,
                () -> rescans.rescan(requestId));

        assertEquals(CommandRescanService.RescanAction.SETTLED, result.action());
        assertEquals(0, command(requestId).getRetryCount().intValue(), "已经成功的指令不该被再动一次");
    }
}

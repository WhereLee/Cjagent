package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.device.DeviceCommandService;
import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDeviceCommand;
import com.wherelee.cabinet.domain.entity.BizDeviceReport;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.CommandState;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.device.SimulatedCabinetChannel;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDeviceCommandMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDeviceReportMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备通道与故障注入（第 11 刀验收）。
 *
 * <p>每条用例断言的是<b>业务在某种故障下的正确反应</b>，不是"模拟器能返回假数据"——
 * 后者没有价值。所以每条都同时看三处：指令状态、订单状态、故障事件。
 *
 * <p>故障<b>按柜机定向</b>注入（不是全局模式）：全局注入会把同库里别的柜机一起打挂，
 * 用例之间互相污染，最后只能靠执行顺序运气绿。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.device.receipt-timeout-ms=400",
        "cabinet.device.fail-threshold=3",
        "cabinet.simulator.reply-delay-ms=0"
})
class DeviceFaultInjectionTest {

    private static final Long TENANT = 8101L;

    @Autowired
    private StorageOrderFacade facade;
    @Autowired
    private DeviceCommandService commandService;
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
    private BizDeviceReportMapper reportMapper;
    @Autowired
    private BizFaultEventMapper faultMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String cabinetNo;
    private String orderNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        simulator.reset();
        cabinetNo = "CAB-D-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("S-" + cabinetNo);
            site.setName("故障注入点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("故障注入柜机");
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

            orderNo = TenantContext.callAs(TENANT, () -> facade.create(930001L,
                    new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        });
    }

    @AfterEach
    void cleanup() {
        simulator.reset();
        redis.delete("cab:dev:fail:" + cabinetId);
        jdbc.update("delete from biz_device_report where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "S-" + cabinetNo);
        TenantContext.clear();
    }

    private BizStorageOrder order() {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private BizDeviceCommand lastCommand(CommandAction action) {
        return TenantContext.callAs(TENANT, () -> commandMapper.selectOne(
                Wrappers.<BizDeviceCommand>lambdaQuery()
                        .eq(BizDeviceCommand::getCabinetId, cabinetId)
                        .eq(BizDeviceCommand::getAction, action)
                        .orderByDesc(BizDeviceCommand::getId)
                        .last("limit 1")));
    }

    private BizDeviceCommand commandByRequestId(String requestId) {
        return TenantContext.callAs(TENANT, () -> commandMapper.selectByRequestId(requestId));
    }

    private FaultType firstFault() {
        BizFaultEvent event = TenantContext.callAs(TENANT, () -> faultMapper.selectOne(
                Wrappers.<BizFaultEvent>lambdaQuery()
                        .eq(BizFaultEvent::getCabinetId, cabinetId)
                        .orderByDesc(BizFaultEvent::getId).last("limit 1")));
        return event == null ? null : event.getFaultType();
    }

    @Test
    @DisplayName("正常路径：OPEN 后门开、CLOSE_VERIFY 后才记为已存（不能提前记账）")
    void normalOpenThenCloseVerify() {
        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));
        assertEquals("OPENING", order().getStatus().name(), "门开了但还没关门校验，不得提前记为已存");
        assertEquals(CommandState.SUCCEEDED, lastCommand(CommandAction.OPEN).getStatus());

        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.CLOSE_VERIFY));
        assertEquals("STORED", order().getStatus().name());
        assertEquals(2, TenantContext.callAs(TENANT, () -> reportMapper.selectCount(
                Wrappers.<BizDeviceReport>lambdaQuery().eq(BizDeviceReport::getCabinetId, cabinetId))),
                "两次动作应各留一条上报流水");
        assertNull(firstFault(), "正常路径不该产生故障事件");
    }

    @Test
    @DisplayName("无回执：指令 TIMEOUT、订单退回可重试、格口不释放")
    void noReceiptKeepsRetryable() {
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.NO_RECEIPT);

        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));

        assertEquals("RESERVED", order().getStatus().name(), "没开成门就要能重试，不能卡在 OPENING");
        assertEquals(CommandState.TIMEOUT, lastCommand(CommandAction.OPEN).getStatus());
        assertEquals(FaultType.NO_REPORT, firstFault());
        assertEquals(SlotStatus.RESERVED, slotStatus(), "格口必须仍被这张单占住，否则会被别人抢走");
    }

    @Test
    @DisplayName("谎报关门：指令层成功但业务不认账，订单进异常等人工")
    void lyingCloseRequiresManual() {
        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.LYING_CLOSED);

        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.CLOSE_VERIFY));

        assertEquals("ABNORMAL", order().getStatus().name(),
                "设备说门关了但门磁没确认：件已在柜内，必须人工核验，不能记为已存");
        assertEquals(CommandState.SUCCEEDED, lastCommand(CommandAction.CLOSE_VERIFY).getStatus(),
                "指令层面设备确实执行完了——两个层次的\"成功\"必须分开");
        assertEquals(FaultType.DOOR_NOT_CLOSED, firstFault());
    }

    @Test
    @DisplayName("错报目标（部分成功/串位）：不认账，订单退回可重试并记故障")
    void wrongTargetRejected() {
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.WRONG_TARGET);

        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));

        assertEquals("RESERVED", order().getStatus().name());
        assertEquals(CommandState.FAILED, lastCommand(CommandAction.OPEN).getStatus());
        assertEquals(FaultType.TARGET_MISMATCH, firstFault(),
                "设备开了别的格子：绝不能把用户的单绑到那个格口上");
    }

    @Test
    @DisplayName("乱序重放：旧序号只留证不改变状态")
    void staleSeqDoesNotChangeState() {
        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.STALE_SEQ);

        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.CLOSE_VERIFY));

        assertEquals("OPENING", order().getStatus().name(),
                "设备重启后 seq 归零重放，不得把新状态覆盖成旧事件");
        assertEquals(CommandState.SENT, lastCommand(CommandAction.CLOSE_VERIFY).getStatus(),
                "旧事件不该推进指令终态");
    }

    @Test
    @DisplayName("连续失败达阈值：格口标故障、柜机停用，不再接客")
    void consecutiveFailDisablesCabinet() {
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.EXEC_FAIL);
        for (int i = 0; i < 3; i++) {
            TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));
        }
        assertEquals(CabinetStatus.DISABLED, cabinetStatus(), "坏柜机必须停用，否则用户会一台接一台踩坑");
        assertEquals(SlotStatus.FAULT, slotStatus());
        assertEquals(FaultType.CONSECUTIVE_FAIL, firstFault());
    }

    @Test
    @DisplayName("柜机离线：存件方向直接拒绝，且订单不卡在 OPENING")
    void offlineRejectsStoreAndLeavesOrderRetryable() {
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.OFFLINE);

        BizException e = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN)));
        assertEquals(ResultCode.MIDDLEWARE_UNAVAILABLE.getCode(), e.getResultCode().getCode());
        assertEquals("RESERVED", order().getStatus().name(), "离线不能把单留在 OPENING");
        assertEquals(FaultType.OFFLINE, firstFault());
    }

    @Test
    @DisplayName("requestId 幂等：同一次尝试重复下发只产生一条指令")
    void dispatchIsIdempotentByRequestId() {
        BizCabinet cabinet = TenantContext.callAs(TENANT, () -> cabinetMapper.selectById(cabinetId));
        Long slotId = TenantContext.callAs(TENANT, () -> order().getSlotId());
        String requestId = "idem-" + UUID.randomUUID();

        var first = TenantContext.callAs(TENANT,
                () -> commandService.dispatch(cabinet, slotId, CommandAction.OPEN, order().getId(), requestId));
        var second = TenantContext.callAs(TENANT,
                () -> commandService.dispatch(cabinet, slotId, CommandAction.OPEN, order().getId(), requestId));

        assertEquals(first.commandId(), second.commandId(), "同一 requestId 必须复用同一条指令");
        assertNotNull(commandByRequestId(requestId));
        assertEquals(1, TenantContext.callAs(TENANT, () -> commandMapper.selectCount(
                Wrappers.<BizDeviceCommand>lambdaQuery().eq(BizDeviceCommand::getRequestId, requestId))));
        assertTrue(second.message().contains("重复"), "命中幂等要能看出来，否则排障时像\"什么都没发生\"");
    }

    @Test
    @DisplayName("重试换新 requestId：同一动作的第 2 次尝试不会被幂等堵死")
    void retryGetsNewRequestId() {
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.EXEC_FAIL);
        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));
        String firstId = lastCommand(CommandAction.OPEN).getRequestId();

        simulator.reset();
        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.OPEN));

        String secondId = lastCommand(CommandAction.OPEN).getRequestId();
        assertNotEquals(firstId, secondId, "重试必须换键，否则会拿回上一次的失败结果");
        assertTrue(firstId.endsWith(":1") && secondId.endsWith(":2"), "requestId 形如 单号:动作:第几次");
        assertEquals("STORED", retryToStored(), "恢复后应能正常走完开柜与关门校验");
    }

    /** 第三次调用是 CLOSE_VERIFY，走完后应为 STORED。 */
    private String retryToStored() {
        TenantContext.runAs(TENANT, () -> facade.openDoor(930001L, orderNo, CommandAction.CLOSE_VERIFY));
        return order().getStatus().name();
    }

    private SlotStatus slotStatus() {
        Long slotId = TenantContext.callAs(TENANT, () -> order().getSlotId());
        return TenantContext.callAs(TENANT, () -> slotMapper.selectById(slotId)).getStatus();
    }

    private CabinetStatus cabinetStatus() {
        return TenantContext.callAs(TENANT, () -> cabinetMapper.selectById(cabinetId)).getCabinetStatus();
    }
}

package com.wherelee.cabinet.storage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.infra.SimulatedCabinetChannel;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.door-watch.first-remind-minutes=0",
})
@DisplayName("结束后遗留物：发现者上报、锁格、弱锁不覆盖强锁")
class ItemDisputeFlowTest {

    private static final Long TENANT = 8118L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private PointAccountService points;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private CompartmentStateService states;
    @Autowired
    private SimulatedCabinetChannel simulator;

    private JdbcTemplate jdbc;
    private String cabinetNo;
    private Long cabinetId;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("T-" + cabinetNo);
            site.setName("遗留物点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("遗留物柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

            for (SizeType size : SizeType.values()) {
                for (int i = 1; i <= 3; i++) {
                    BizCompartment slot = new BizCompartment();
                    slot.setCabinetId(cabinetId);
                    slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
                    slot.setSizeType(size);
                    slot.setStatus(SlotStatus.FREE);
                    slotMapper.insert(slot);
                }
            }
        });
        simulator.reset();
    }

    @AfterEach
    void cleanup() {
        simulator.reset();
        Object[] customers = customerIds.toArray();
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_notice where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_item_evidence where cabinet_id = ?", cabinetId);
        if (!customerIds.isEmpty()) {
            jdbc.update("delete from biz_point_txn where customer_id in (" + placeholders() + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + placeholders() + ")", customers);
        }
        jdbc.update("delete from biz_device_report where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "T-" + cabinetNo);
        customerIds.clear();
        TenantContext.clear();
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private Long newCustomer() {
        Long id = 980000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        return id;
    }

    private BizStorageOrder newOrder(Long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "遗留物用例 funding"));
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))).orderNo();
        return order(orderNo);
    }

    private BizStorageOrder order(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private BizCompartment slot(Long slotId) {
        return TenantContext.callAs(TENANT, () -> slotMapper.selectById(slotId));
    }

    private void door(String orderNo, Long customerId, CommandAction action) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, action));
    }

    private BizStorageOrder activate(Long customerId, BizStorageOrder order) {
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        return order(order.getOrderNo());
    }

    private BizStorageOrder closeWithItemLeft(Long customerId) {
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));
        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus(),
                "柜里有东西也不能把用户钉在柜机前：照收钱、照关闭（定-13）");
        simulator.simulateDoor(cabinetId, closed.getSlotId(), true);
        BizCompartment openedSlot = slot(closed.getSlotId());
        TenantContext.runAs(TENANT, () -> states.afterDoorClosed(openedSlot, customerId));
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(order.getSlotId()).getAnomaly(),
                "不拦人不等于不记：门关了还检到东西，就必须把这格标成遗留物并进工单（定-14）");
        return closed;
    }

    @Test
    @DisplayName("发现者上报：漏检的格口被下一位使用者看到时，锁格、上报者免费退单、原单打标")
    void leftoverReportLocksSlotAndRefundsReporter() {
        Long owner = newCustomer();
        BizStorageOrder first = activate(owner, newOrder(owner));
        simulator.simulateItemInside(cabinetId, first.getSlotId(), true);
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.ITEM_UNDETECTED);
        TenantContext.runAs(TENANT, () -> orderService.pickup(owner, first.getOrderNo()));
        BizCompartment justOpened = slot(first.getSlotId());
        TenantContext.runAs(TENANT, () -> states.afterDoorClosed(justOpened, owner));

        BizStorageOrder closedFirst = order(first.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closedFirst.getStatus());
        assertNull(slot(closedFirst.getSlotId()).getAnomaly(), "漏检下系统认为它是空的，没留任何标记");

        jdbc.update("update biz_compartment set status = 'MAINTENANCE' "
                        + "where cabinet_id = ? and size_type = 'SMALL' and id <> ?",
                cabinetId, closedFirst.getSlotId());

        Long reporter = newCustomer();
        BizStorageOrder second = newOrder(reporter);
        assertEquals(closedFirst.getSlotId(), second.getSlotId(), "用例前提：他抢到的就是那一格");
        door(second.getOrderNo(), reporter, CommandAction.OPEN);
        TenantContext.runAs(TENANT, () -> orderService.reportLeftover(reporter, second.getOrderNo()));

        BizStorageOrder released = order(second.getOrderNo());
        assertEquals(OrderStatus.CANCELLED, released.getStatus());
        assertEquals(0L, released.getSettledPoints(), "上报者必须全身而退：要他付钱，下次他就直接走人不报了");
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(closedFirst.getSlotId()).getAnomaly(),
                "上报之后这一格成了遗留物，只能由人清走");
        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, SizeType.SMALL)))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(closedFirst.getSlotId()), "被上报的格口不得再被分配");
        assertNotNull(order(closedFirst.getOrderNo()).getLeftoverReportedAt(),
                "原主侧要能看到这件事（他不在场时唯一的告知路径）");
    }

    @Test
    @DisplayName("已知遗留物的格口不会被分给下一位：锁只由标异常产生，不再需要一个“声明放弃”动作")
    void reportedCompartmentNeverReappearsInCandidates() {
        Long owner = newCustomer();
        Long slotId = closeWithItemLeft(owner).getSlotId();

        TenantContext.runAs(TENANT, () -> states.tryAutoRecover(slotId,
                new Sensing(true, Presence.ABSENT, LocalDateTime.now())));
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(slotId).getAnomaly(), "I9：遗留物只能由人放行");

        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, SizeType.SMALL)))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(slotId), "已经标了遗留物的格子不该再出现在候选里");
    }

    @Test
    @DisplayName("弱锁不覆盖强锁：一次探测失败不能把遗留物格口降级")
    void weakerLockNeverDowngradesStrongerOne() {
        Long customerId = newCustomer();
        Long slotId = closeWithItemLeft(customerId).getSlotId();

        TenantContext.runAs(TENANT, () -> states.markContentUnverified(slotId, "模拟一次无凭据的结束"));
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(slotId).getAnomaly(),
                "遗留物是已经看见有东西，测不到只是没凭据；弱锁盖强锁会让被上报的格子被当空柜清走");
    }
}

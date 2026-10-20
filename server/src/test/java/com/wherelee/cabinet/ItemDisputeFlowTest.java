package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
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

/**
 * 结束之后的遗留物：发现者上报、锁格、弱锁不覆盖强锁。
 *
 * <p>这个类以前叫"争议阶梯验收"，里面有六条用例靠<b>拒绝结束</b>来制造现场。19A 把结束
 * 改成一律不拦人之后，那六条的前提不成立了；2026-10-12 用户拍定"误报免除作废"，
 * 那六条连同 `denyItem` / `waiveDisputeFee` / AI 复审换结束许可那条链一起删除（不是禁用）。
 *
 * <p>留在这里的三条测的是**今天生产上唯一可达**的路径：
 * <ul>
 *   <li>结束不拦人 → 照收钱并关闭 → 物检检到东西 → 这一格标遗留物异常并进工单；</li>
 *   <li>漏检（传感器看不见）时，下一位使用者上报：他这一单免费退回，格子锁起来不再卖，
 *       原主的单上打标（他不在场时唯一的告知路径）；</li>
 *   <li>弱锁不覆盖强锁：一次"没测到"不能把"确实看见有东西"降级成可售。</li>
 * </ul>
 *
 * <p>为什么上报者必须全身而退：要他为不是自己造成的现场付钱，得到的只会是下次直接走人不报，
 * 那件东西就会一直躺在格子里被卖给第三个人。
 */
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
        // 第 21 刀起 markContentLeft 会给物主发站内消息，按租户清掉避免共享库堆积
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
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
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

    /**
     * 新口径下的“结束后现场真有东西”：19A 不拦人，所以结束会照收钱并关闭，
     * 而物检检到东西就把这一格标成遗留物异常。这也是旧写法里拿 `abandon`
     * （声明放弃，入口已删）造出现场的替换。
     */
    private BizStorageOrder closeWithItemLeft(Long customerId) {
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));
        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus(),
                "柜里有东西也不能把用户钉在柜机前：照收钱、照关闭（定-13）");
        // 定-17（2026-10-12 修正时机）：遗留与否不在点结束时判——那时柜里必然还是他自己的东西，
        // 判了就是每次正常取物都误报一次遗弃。等他取完东西、把门关上之后那次检测才判
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
        // 系统“看不见”这件东西（小件/透明物的现实盲区）：门关、物检答无物 → 正常结束、格口回可售。
        // 这才是发现者路径要救的现场：不是“已知有东西”，而是“我们以为它是空的”
        simulator.simulateItemInside(cabinetId, first.getSlotId(), true);
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.ITEM_UNDETECTED);
        TenantContext.runAs(TENANT, () -> orderService.pickup(owner, first.getOrderNo()));
        // 结束即开门（定-17 后半句）之后，门开着时这一格不可卖是物理事实：
        // 本用例要走到“下一位抢到同一格”，必须先让他把门关上（漏检下柜内仍被判为空）
        BizCompartment justOpened = slot(first.getSlotId());
        TenantContext.runAs(TENANT, () -> states.afterDoorClosed(justOpened, owner));

        BizStorageOrder closedFirst = order(first.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closedFirst.getStatus());
        assertNull(slot(closedFirst.getSlotId()).getAnomaly(), "漏检下系统认为它是空的，没留任何标记");

        // 把同尺寸其余格口停用，逼下一位只能拿到那一格（不让用例结论取决于分配顺序的巧合）
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

        // 门磁又被确认关上（下一个人来之前它物理上是关的）：异常必须仍在
        TenantContext.runAs(TENANT, () -> states.tryAutoRecover(slotId,
                new CompartmentStateService.Sensing(true, Presence.ABSENT, LocalDateTime.now())));
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

        // 门磁不确认时不降级：“遗留物”已经比“没凭据”更强
        TenantContext.runAs(TENANT, () -> states.markContentUnverified(slotId, "模拟一次无凭据的结束"));
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(slotId).getAnomaly(),
                "遗留物是已经看见有东西，测不到只是没凭据；弱锁盖强锁会让被上报的格子被当空柜清走");
    }
}

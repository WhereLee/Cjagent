package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.SlotCandidateQuery;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.device.SimulatedCabinetChannel;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门态、物检与结束判据（第 13B 刀验收，规则见 docs/门态与物品争议设计.md）。
 *
 * <p>这一刀真正要证明的是一句话：<b>门关了不等于柜内是空的</b>。所以每条用例都同时看三处——
 * 订单状态（钱）、格口门态与异常（货）、以及“下一位还能不能被分到这一格”（卖错与否）。
 * 只看订单状态是不够的：钱对了但把别人还留着东西的格子卖出去，仍然是事故。
 *
 * <p>设备侧只借两个能力：把某格口“柜内有物”当真、把某柜机的物检设成不可用。
 * 这就是模拟器该有的样子——能回答我们要问的问题，不多做物理还原。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        // 容错期设 0：用例自己决定"到期前 / 到期后"，不靠等待
        "cabinet.pricing.tolerance-minutes=0",
})
@DisplayName("门态与柜内物品：计费锚点、结束三条件、放弃与远程结束")
class DoorAndContentFlowTest {

    private static final Long TENANT = 8117L;

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
        cabinetNo = "CAB-D-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("D-" + cabinetNo);
            site.setName("门态点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("门态柜机");
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
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        if (!customerIds.isEmpty()) {
            jdbc.update("delete from biz_point_txn where customer_id in (" + placeholders() + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + placeholders() + ")", customers);
        }
        jdbc.update("delete from biz_device_report where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "D-" + cabinetNo);
        customerIds.clear();
        TenantContext.clear();
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private Long newCustomer() {
        Long id = 970000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        return id;
    }

    private BizStorageOrder newOrder(Long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "门态用例 funding"));
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

    /** 开柜 + 关门校验，把单推到计费中。 */
    private BizStorageOrder activate(Long customerId, BizStorageOrder order) {
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        return order(order.getOrderNo());
    }

    @Test
    @DisplayName("容错期内关门：起点就是关门时刻，而不是下单也不是开门")
    void billingStartsOnCloseInsideTolerance() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));

        assertEquals(OrderStatus.ACTIVE, order.getStatus());
        assertNotNull(order.getStartedAt(), "关门确认后必须已开始计费");
        assertFalse(order.getStartedAt().isBefore(order.getCreateTime()),
                "起点不可能早于下单；早于开门更是错的（那时他还没占用任何东西）");
        assertNull(order.getFinishedAt());
        assertNull(slot(order.getSlotId()).getAnomaly(), "门关且无物：不该留下任何异常");
    }

    @Test
    @DisplayName("门未关超过容错期：起点回溯到容错到期，且此后不再被改写（I10）")
    void toleranceExpiryFixesStartAndLaterCloseCannotMoveIt() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);

        // 把容错推到过去再补一次“到期后的第一次巡检”（tolerance-minutes=0 时巡检就是这一刻）
        LocalDateTime tolerance = LocalDateTime.now().minusMinutes(7);
        jdbc.update("update biz_storage_order set tolerance_until = ? where order_no = ?",
                tolerance, order.getOrderNo());
        TenantContext.runAs(TENANT, () -> orderService.toleranceExpired(order.getOrderNo(),
                new com.wherelee.cabinet.application.storage.CompartmentStateService
                        .Sensing(false, com.wherelee.cabinet.domain.enums.Presence.ABSENT, LocalDateTime.now())));

        BizStorageOrder afterWatch = order(order.getOrderNo());
        assertEquals(OrderStatus.ACTIVE, afterWatch.getStatus(), "门开着超过容错也要进入计费态，而不是停在等待关门");
        assertEquals(tolerance.withNano(0), afterWatch.getStartedAt().withNano(0),
                "起点必须是容错到期那一刻：早了多收、晚了少收，两头都不对");

        // 他后来回来关上了门：起点不许被刷成关门时刻（那等于把已发生的 7 分钟抹掉）
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        BizStorageOrder afterClose = order(order.getOrderNo());
        assertEquals(tolerance.withNano(0), afterClose.getStartedAt().withNano(0),
                "计费起点一旦落定就不许再改，否则账单和逾期都在读一个会飘的数字");
    }

    @Test
    @DisplayName("门开着就是不可分配：候选查询直接把它排除（I7）")
    void openDoorSlotIsNeverAcandidate() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        BizCompartment slot = slot(order.getSlotId());
        assertNotNull(slot.getDoorOpenAt(), "开门起点必须落库：计费时长与可分配判定都据它");

        // 注意拦下它的是 door_open_at 而不是 anomaly：异常要等容错期满才标（容错期内开着是正常态），
        // 如果可分配只看过不过异常，容错期内就会把锁不上的格子卖出去
        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, List.of(SizeType.SMALL))))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(order.getSlotId()),
                "门开着的格口绝不能出现在候选里：用户会拿到一个锁不上的格子");
    }

    @Test
    @DisplayName("遗留物异常不会因为下一次关门自动解除（I9：只能由人放行）")
    void contentLeftSurvivesNextClose() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);
        TenantContext.runAs(TENANT, () -> orderService.abandon(customerId, order.getOrderNo()));
        Long slotId = order(order.getOrderNo()).getSlotId();

        // 门是关着的、物检也报“没有”，也必须等人来清：自动解除只服务 DOOR_OPEN
        TenantContext.runAs(TENANT, () -> states.tryAutoRecover(slotId,
                new CompartmentStateService.Sensing(true, Presence.ABSENT, LocalDateTime.now())));

        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(slotId).getAnomaly(),
                "CONTENT_LEFT 被自动解除了：门一关就把别人还留着东西的格子放回可售池，违反 I9");
        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, SizeType.SMALL)))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(slotId), "异常格口不该出现在候选里");
    }
    @Test
    @DisplayName("柜内有物：拒绝结束、计费不停；放弃才能结束，而格口转异常（I12）")
    void contentLeftBlocksPickupThenAbandonMarksAnomaly() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);

        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        assertTrue(rejected.getMessage().contains("物品"), "文案要说清缺哪一条：" + rejected.getMessage());

        BizStorageOrder stillRunning = order(order.getOrderNo());
        assertEquals(OrderStatus.ACTIVE, stillRunning.getStatus(), "有东西就不许停表");
        assertNull(stillRunning.getFinishedAt(), "没结束就不能有结束时刻");
        assertNull(stillRunning.getCloseReason(), "被拒的结束不该留下任何终态痕迹");

        // 他明确说“里面的东西不要了”
        TenantContext.runAs(TENANT, () -> orderService.abandon(customerId, order.getOrderNo()));

        BizStorageOrder abandoned = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, abandoned.getStatus(), "放弃物品可以结束计费");
        assertEquals(OrderCloseReason.ABANDONED, abandoned.getCloseReason());
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(abandoned.getSlotId()).getAnomaly(),
                "钱到此为止，但这一格没被清走之前不是空位");
    }

    @Test
    @DisplayName("远程结束：门还开着就被拒，且计费继续走")
    void remoteCloseRefusedWhileDoorOpen() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        // 先把单推到“计费中”（容错期满、门仍开着），否则拒绝来自状态机而不是门判据，
        // 测到的就不是“远程不能绕过关门”这件事
        TenantContext.runAs(TENANT, () -> orderService.toleranceExpired(order.getOrderNo(),
                new CompartmentStateService.Sensing(false, Presence.ABSENT, LocalDateTime.now())));

        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.remoteClose(customerId, order.getOrderNo())));
        assertTrue(rejected.getMessage().contains("门"), "要说清是门没关：" + rejected.getMessage());
        assertEquals(OrderStatus.ACTIVE, order(order.getOrderNo()).getStatus(),
                "远程结束失败不能把单推到终态，它还得继续计费");
    }

    @Test
    @DisplayName("远程结束成功：计时费 + 该格口 2 小时单价的加收，单独一条流水")
    void remoteCloseChargesPenaltyAsSeparateTxn() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));

        TenantContext.runAs(TENANT, () -> orderService.remoteClose(customerId, order.getOrderNo()));

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus());
        assertEquals(OrderCloseReason.REMOTE, closed.getCloseReason());
        // SMALL 单价 15 点/小时，remote-close-hours=2
        assertEquals(30L, closed.getRemoteClosePoints(), "加收必须按快照单价折算，而不是拿今天的配置");

        Integer feeTxns = jdbc.queryForObject(
                "select count(*) from biz_point_txn where customer_id = ? and biz_no = ?",
                Integer.class, customerId, closed.getOrderNo() + ":remote-fee");
        assertEquals(1, feeTxns, "加收要单独一条流水：账单得能说清哪部分是租金、哪部分是加收");

        BizCompartment slot = slot(closed.getSlotId());
        assertNull(slot.getAnomaly(), "门关且无物的远程结束是干净收尾，不该留异常");
        assertEquals(SlotStatus.FREE, slot.getStatus(), "格口必须真回到可售");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)), "加收之后账仍要平");
    }

    @Test
    @DisplayName("物检不可用：现场能结束但格口被锁住等确认，远程直接拒")
    void unverifiableCloseLocksSlotButRefusesRemote() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        // 这台柜机的柜内传感器不报告结果（坏了/离线）：门磁照常说，但“有没有东西”只能答 UNKNOWN
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.SENSOR_NO_ITEM);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        assertEquals(OrderStatus.ACTIVE, order(order.getOrderNo()).getStatus(),
                "关门校验只判门，不判柜内：传感器坏了不该把投件流程卡死");

        // 远程提交：拿不到凭据就不许停表（他看不见现场）
        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.remoteClose(customerId, order.getOrderNo())));
        assertTrue(rejected.getMessage().contains("无法确认"), "远程要凭据：" + rejected.getMessage());

        // 现场当面结束：不拦人，但格子不能不设防地回到可售池
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus(), "现场不该被一台坏传感器钉在柜机前");
        assertEquals(CompartmentAnomaly.CONTENT_UNVERIFIED, slot(closed.getSlotId()).getAnomaly(),
                "没有凭据的结束必须把格口锁住（否则就是无凭据地把格子放回可售池）");
        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, SizeType.SMALL)))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(closed.getSlotId()),
                "待确认清空的格口不得出现在候选集里：下一位拿到的可能是别人没取走的箱子");
    }

    @Test
    @DisplayName("取消也要看柜内：有东西就不许取消（否则钱退了件留下）")
    void cancelRefusedWhenContentPresent() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);

        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.cancel(customerId, order.getOrderNo())));
        assertTrue(rejected.getMessage().contains("物品"),
                "取消不能比结束更松：钱退了、件留在柜里、格子又卖给别人是这里最坏的现场");
    }
}

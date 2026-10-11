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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        "cabinet.door-watch.first-remind-minutes=0",
        // 定-23 的预备开重试间隔归零：本类验的是“重试几次、何时换格、何时退单”这个顺序，不是等 sleep
        "cabinet.device.open-retry-interval-ms=0",
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
    @Autowired
    private com.wherelee.cabinet.application.storage.CabinetAvailabilityService availabilityService;
    @Autowired
    private com.wherelee.cabinet.application.storage.CompartmentStateService compartmentStates;
    @Autowired
    private com.wherelee.cabinet.application.storage.StorageQueryService queries;

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
    @DisplayName("开柜瞬间就落计费起点：暂时开柜与后来关门都改不了它（I10）")
    void openDoorFixesStartAndNothingLaterCanMoveIt() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        LocalDateTime beforeOpen = LocalDateTime.now();
        door(order.getOrderNo(), customerId, CommandAction.OPEN);

        BizStorageOrder opened = order(order.getOrderNo());
        assertNotNull(opened.getStartedAt(), "19A：开柜成功那一刻就必须有计费起点，不等关门");
        assertFalse(opened.getStartedAt().isBefore(beforeOpen.minusMinutes(1)),
                "起点只能是开门时刻，不能比下单还早");
        LocalDateTime start = opened.getStartedAt();

        // 中途取物：再开一次门不得重新发半小时免费（起点不动）。临时开柜要求单在计费中，
        // 所以先把门关回去进入 ACTIVE，再点临时开柜
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                CommandAction.OPEN_TEMP));
        assertEquals(start.withNano(0), order(order.getOrderNo()).getStartedAt().withNano(0),
                "暂时开柜不得把起点往后推：那等于每开一次门就白得一段免费时间");

        // 关回来了：起点仍不许动（只是停止“门未关”那个异常的前进）
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        assertEquals(start.withNano(0), order(order.getOrderNo()).getStartedAt().withNano(0),
                "起点一旦落定就不许再改，否则账单与逾期都在读一个会飘的数字");
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
        // 19A：柜内有物不再拦结束。他自己点结束，单就结、费就收；
        // 但这一格在有人清走东西之前不算空位（I9/I12：“记事实”取代了“拦人”）
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));
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
    @DisplayName("柜内有物：不拦结束也不停表；结束后格口转滞留异常（I12）")
    void contentLeftDoesNotBlockPickupButMarksAnomaly() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);

        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus(), "19A：柜内有东西也不能把用户钉在柜机前");
        assertNotNull(closed.getFinishedAt());
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(closed.getSlotId()).getAnomaly(),
                "钱到此为止，但这一格没被清走之前不是空位");
        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, SizeType.SMALL)))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(closed.getSlotId()), "滞留格口不得被分配给下一位");
    }

    @Test
    @DisplayName("门还开着也能结束：单进终态、计费照收、格口挂“门未关”异常")
    void endingWithDoorOpenStillClosesAndMarksDoorOpenAnomaly() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        TenantContext.runAs(TENANT, () -> orderService.doorWatchTick(order.getOrderNo(),
                new CompartmentStateService.Sensing(false, Presence.ABSENT, LocalDateTime.now())));

        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus(), "旧口径里“门没关就拒绝结束”已作废（他会卡在柜机前）");
        // 结束把单与格口解绑是对的（钱已清、物主已走），但这一格仍不可售：
        // 拦它的不是 status 而是“门还开着 + 挂着 DOOR_OPEN 异常”，两者都在候选判据里
        assertEquals(CompartmentAnomaly.DOOR_OPEN, slot(closed.getSlotId()).getAnomaly(),
                "不拦人不等于不记：门未关必须进台账（19D 接提醒阶梯与工单）");
        assertNotNull(slot(closed.getSlotId()).getDoorOpenAt(),
                "门未关这个物理事实必须留着：候选判据靠它把这一格排除在外");
        List<Long> candidates = TenantContext.callAs(TENANT, () -> slotMapper
                .selectList(SlotCandidateQuery.assignable(cabinetId, SizeType.SMALL)))
                .stream().map(BizCompartment::getId).toList();
        assertFalse(candidates.contains(closed.getSlotId()), "门还开着的格口不得被分配给下一位");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)), "账仍必须平");
    }

    @Test
    @DisplayName("结束不再有任何“未关门加收”：加收随远程结束入口一起作废")
    void endingChargesNoPenaltyAnyMore() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));

        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus());
        assertEquals(0L, closed.getRemoteClosePoints(), "2026-10-11 定稿：结束本来就是在手机上点的，不再加收");
        Integer feeTxns = jdbc.queryForObject(
                "select count(*) from biz_point_txn where customer_id = ? and biz_no = ?",
                Integer.class, customerId, closed.getOrderNo() + ":remote-fee");
        assertEquals(0, feeTxns, "加收流水不得再产生：留着就是“两个入口两个价”那种歧义源头");
        BizCompartment slot = slot(closed.getSlotId());
        assertNull(slot.getAnomaly(), "门关且无物的结束是干净收尾");
        assertEquals(SlotStatus.FREE, slot.getStatus(), "格口必须真回到可售");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)), "账仍要平");
    }

    @Test
    @DisplayName("物检测不准：同样不拦结束，但格口锁住等确认（不凭“猜”放回可售）")
    void unverifiableCloseLocksSlotInsteadOfRefusing() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        // 这台柜机的柜内传感器不报结果（坏了/离线）：门磁照常说，但“有没有东西”只能答 UNKNOWN
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.SENSOR_NO_ITEM);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        assertEquals(OrderStatus.ACTIVE, order(order.getOrderNo()).getStatus(),
                "关门校验只判门，不判柜内：传感器坏了不该把流程卡死");

        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo()));

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus(), "用户不该被一台坏传感器钉在柜机前");
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

    @Test
    @DisplayName("柜机回报预备开失败：重试完→换格→仍不成才退单退预收（定-21/定-23）")
    void prepareFailureRetriesSwapsThenRefunds() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        Long firstSlot = order.getSlotId();
        // 预备开失败是“确认坏了”：门肯定没动过，所以这一步可以安全换格与退钱
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.PREPARE_FAIL);

        BizException failed = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> orderService.openDoor(customerId, order.getOrderNo(), CommandAction.OPEN)));
        assertTrue(failed.getMessage().contains("退单"),
                "文案要给下一步而不是报错：“" + failed.getMessage() + "”");

        BizStorageOrder after = order(order.getOrderNo());
        assertEquals(OrderStatus.CANCELLED, after.getStatus(), "预备开就确认坏了，门肯定没动：不能留下一个占着格口的单");
        assertNotEquals(firstSlot, after.getSlotId(), "中途确实试过换格（定-21）");
        assertEquals(5000L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints(),
                "一分都没收到：预收必须整笔退回");
        assertTrue(jdbc.queryForObject(
                "select count(*) from biz_compartment where cabinet_id = ? and status = 'FAULT'",
                Integer.class, cabinetId) >= 1,
                "打不开的格口要摘出可分配池，否则下一个人还会撞上");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ? and cabinet_id = ? and type = 'DEVICE_FAULT'"
                        + " and active_flag = 1",
                Integer.class, TENANT, cabinetId), "故障必须成为一张工单，不能只活在日志里");
    }

    @Test
    @DisplayName("整柜不回话不得退单：无回执维持现状（定-4），钱与单都不动")
    void noReceiptKeepsMoneyUntouched() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.NO_RECEIPT);

        // 无回执不抛异常也不退单（维持定-23 之前的行为：交给回扫与待收敛回收）
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(), CommandAction.OPEN));
        assertNotEquals(OrderStatus.CANCELLED, order(order.getOrderNo()).getStatus(),
                "不回话不是“确认坏了”：自动退单会把格子卖下一个人，而柜里可能已经有件");
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ? and cabinet_id = ? and type = 'DEVICE_FAULT'",
                Long.class, TENANT, cabinetId).longValue(),
                "无回执时不得提前判故障开单（查本柜机，不拿共享库的全局计数当凭据）");
    }

    @Test
    @DisplayName("预备开不碰物理：没有任何格口被写成“门开过”，也没计费起点")
    void prepareFailureTouchesNoPhysics() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        simulator.setFaultFor(cabinetId, SimulatedCabinetChannel.Fault.PREPARE_FAIL);

        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> orderService.openDoor(customerId, order.getOrderNo(), CommandAction.OPEN)));

        assertEquals(0, jdbc.queryForObject(
                "select count(*) from biz_compartment where cabinet_id = ? and door_open_at is not null",
                Integer.class, cabinetId).intValue(), "预备开不得写门态：否则“问了”会变成“开了”");
        assertNull(order(order.getOrderNo()).getStartedAt(), "计费起点只能由真开成功落定");
    }

    @Test
    @DisplayName("剩余数只算正常可分配格口：标异常就减一，全标异常就是 0 且下单被拒")
    void availabilityCountsOnlyAssignableSlots() {
        long before = freeSmall();
        assertTrue(before > 0, "夹具里这台柜机得有小格可用，不然这条用例测不出什么：" + before);
        java.util.List<Long> smalls = jdbc.queryForList(
                "select id from biz_compartment where tenant_id = ? and cabinet_id = ? and size_type = 'SMALL'"
                        + " and status = 'FREE' and anomaly is null and door_open_at is null",
                Long.class, TENANT, cabinetId);

        TenantContext.runAs(TENANT, () -> compartmentStates.markContentLeft(smalls.get(0), "用例：检出遗留物"));
        assertEquals(before - 1, freeSmall(), "标了异常的格口不得再计入剩余：它已经不能卖给人家了");

        for (Long id : smalls) {
            TenantContext.runAs(TENANT, () -> compartmentStates.markContentLeft(id, "用例：全部标异常"));
        }
        assertEquals(0, freeSmall(), "一格可用都没有时屏上就该写 0，不能写个让人白跑一趟的数");

        Long customerId = newCustomer();
        BizException soldOut = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> orderService.create(customerId, new CreateOrderCommand(
                        java.util.UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))));
        assertTrue(soldOut.getMessage().contains("没有可用"),
                "屏幕说 0，下单就得说没格：两处必须同一个答案：" + soldOut.getMessage());
    }

    /** 这台柜机小格的剩余数（走的就是对外接口那一个方法；必须带租户上下文，否则租户隔离插件报错）*/
    private long freeSmall() {
        return TenantContext.callAs(TENANT, () -> availabilityService.of(cabinetNo).sizes().stream()
                .filter(s -> "SMALL".equals(s.sizeType()))
                .mapToLong(com.wherelee.cabinet.application.storage.CabinetAvailabilityService.SizeFree::free)
                .findFirst().orElse(-1L));
    }

    @Test
    @DisplayName("只读查询得把用户能用的信息给齐：单上有柜机与格口编号，钱包上有活动单数")
    void queryViewsCarryWhatTheUserNeeds() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);

        java.util.List<com.wherelee.cabinet.application.storage.StorageQueryService.OrderView> mine =
                TenantContext.callAs(TENANT, () -> queries.myOrders(customerId, 20));
        assertTrue(mine.stream().anyMatch(v -> v.orderNo().equals(order.getOrderNo())),
                "刚下的单必须出现在我的列表里：" + mine);
        var view = mine.stream().filter(v -> v.orderNo().equals(order.getOrderNo())).findFirst().orElseThrow();
        assertEquals(cabinetNo, view.cabinetNo(), "不给柜机编号，用户到了现场对不上哪一台");
        assertNotNull(view.slotNo(), "不给格口编号，用户就只能对着二十个门猜");
        assertNotNull(view.sizeType(), "尺寸得说出来：否则用户不知道自己的件能不能装进去");

        var wallet = TenantContext.callAs(TENANT, () -> queries.wallet(customerId));
        assertEquals(1L, wallet.activeOrders(), "刚下的这张单要算成活动单（一人只能占一格靠它可见）");
        // 不写死数字：本夹具给自己建了点位价目，单价不是默认值。
        // 真正要钉的是口径：单上记了多少预收，余额就应当少多少——两处对不上就是记账分裂
        long prepay = jdbc.queryForObject(
                "select prepay_points from biz_storage_order where order_no = ?", Long.class,
                order.getOrderNo());
        assertEquals(5000L - prepay, wallet.points(),
                "预收真扣了钱，而且扣的数与单上记的是同一个数（余额=充值-预收）");
        assertTrue(prepay > 0L, "下单就必须产生预收，否则 19A 的预付模子形同虚设");
        assertEquals(0L, wallet.arrearsPoints(), "还没结算，不应凭空多出欠费");
    }
}

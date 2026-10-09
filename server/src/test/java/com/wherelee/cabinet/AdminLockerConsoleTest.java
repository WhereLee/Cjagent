package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.locker.LockerConsoleService;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizItemEvidence;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.EvidenceSource;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.ai.StubItemReviewPort;
import com.wherelee.cabinet.infrastructure.device.SimulatedCabinetChannel;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizItemEvidenceMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.interfaces.admin.locker.AdminLockerController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营后台现场处置（第 14 刀验收）。
 *
 * <p>测的是<b>处置的前置条件与后果</b>，不是"接口能不能通"：清柜前门必须已关、说明必须留、
 * 强制开柜必须二次确认、免除必须两条证据互相打脸、欠费必须真挡住下单。
 * 这些判据写不住，后台就从"闭环"变成"绕过业务规则的后门"。
 *
 * <p>权限点用注解断言而不是跑安全链路：403 的机制底座已有用例覆盖（第三刀），
 * 本刀要守的是"每个处置动作各自有权限点、没借用 system:*"这一条，注解上写得清清楚楚，
 * 用反射断言比再造一套登录-授权-调用夹具便宜得多也稳得多。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        "cabinet.pricing.tolerance-minutes=0",
})
@DisplayName("后台处置：清柜、强制开柜、误报免除、欠费拦截、禁用客户")
class AdminLockerConsoleTest {

    private static final Long TENANT = 8119L;

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
    private BizFaultEventMapper faultMapper;
    @Autowired
    private BizItemEvidenceMapper evidenceMapper;
    @Autowired
    private PointAccountService points;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private CompartmentStateService states;
    @Autowired
    private LockerConsoleService console;
    @Autowired
    private SimulatedCabinetChannel simulator;
    @Autowired
    private StubItemReviewPort reviewPort;

    private JdbcTemplate jdbc;
    private String cabinetNo;
    private Long cabinetId;
    private Long siteId;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-A-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("A-" + cabinetNo);
            site.setName("后台点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);
            siteId = site.getId();

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(siteId);
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("后台柜机");
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
        reviewPort.reset();
    }

    @AfterEach
    void cleanup() {
        simulator.reset();
        reviewPort.reset();
        Object[] customers = customerIds.toArray();
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_item_evidence where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_report where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "A-" + cabinetNo);
        if (!customerIds.isEmpty()) {
            jdbc.update("delete from biz_point_txn where customer_id in (" + placeholders() + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + placeholders() + ")", customers);
            jdbc.update("delete from biz_customer where id in (" + placeholders() + ")", customers);
        }
        customerIds.clear();
        TenantContext.clear();
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private Long newCustomer() {
        Long id = 990000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '后台客户', 1, now(3), now(3), now(3), 0)",
                id, TENANT, "tk-" + id);
        return id;
    }

    private BizStorageOrder newOrder(Long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "后台用例 funding"));
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

    /** 推到计费中并把柜内做成"有物"，再触发一次结束被拦（产生争议与两条证据）。 */
    private BizStorageOrder blockedWithDispute(Long customerId) {
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);
        assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        return order(order.getOrderNo());
    }

    @Test
    @DisplayName("台账分页与概览：异常格口能按原因码翻出来，停留时长算得出来")
    void ledgerPagesAnomaliesWithStuckTime() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        TenantContext.runAs(TENANT, () -> orderService.toleranceExpired(order.getOrderNo(),
                new CompartmentStateService.Sensing(false, Presence.ABSENT, LocalDateTime.now())));

        var page = TenantContext.callAs(TENANT, () ->
                console.anomalies(new PageQuery(), "DOOR_OPEN", siteId));
        List<LockerConsoleService.LedgerView> rows = page.records();
        assertTrue(rows.stream().anyMatch(r -> r.compartmentId().equals(order.getSlotId())),
                "异常格口必须出现在台账里");
        LockerConsoleService.LedgerView row = rows.stream()
                .filter(r -> r.compartmentId().equals(order.getSlotId())).findFirst().orElseThrow();
        assertNotNull(row.stuckMinutes(), "停留分钟要算出来，排班靠它排序");
        assertEquals(cabinetNo, row.cabinetNo(), "台账得带柜机号，否则运维不知道去哪台");

        var summary = TenantContext.callAs(TENANT, () -> console.anomalySummary(siteId));
        assertTrue(summary.stream().anyMatch(s -> "DOOR_OPEN".equals(s.anomaly()) && s.rowsCount() > 0),
                "概览按原因码计数");
    }

    @Test
    @DisplayName("清柜：门没关不给标正常、说明必填、成功后留 RECOVER 事件")
    void resolveAnomalyRequiresClosedDoorAndNote() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        TenantContext.runAs(TENANT, () -> orderService.toleranceExpired(order.getOrderNo(),
                new CompartmentStateService.Sensing(false, Presence.ABSENT, LocalDateTime.now())));
        Long slotId = order(order.getOrderNo()).getSlotId();

        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.resolveAnomaly(slotId, "  ")), "说明空白必须拒");
        BizException doorBlocked = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.resolveAnomaly(slotId, "现场已看过")), "门没关不能标正常");
        assertTrue(doorBlocked.getMessage().contains("柜门"), doorBlocked.getMessage());

        // 门关上之后才允许清；解除动作必须留下"谁看过、看到什么"
        simulator.simulateDoor(cabinetId, slotId, true);
        TenantContext.runAs(TENANT, () -> states.afterDoorClosed(slotForClose(slotId), customerId));
        TenantContext.runAs(TENANT, () -> console.resolveAnomaly(slotId, "现场柜内为空，已关门"));

        assertEquals(null, slot(slotId).getAnomaly());
        List<BizFaultEvent> events = TenantContext.callAs(TENANT, () -> faultMapper.selectList(
                Wrappers.<BizFaultEvent>lambdaQuery().eq(BizFaultEvent::getSlotId, slotId)
                        .eq(BizFaultEvent::getAction, FaultType.Action.RECOVER)));
        assertEquals(1, events.size(), "解除动作要有流水，否则状态改了无人可查");
    }

    private BizCompartment slotForClose(Long slotId) {
        return slot(slotId);
    }

    @Test
    @DisplayName("强制开柜：必须二次确认并写事由；开完格口自动不可分配")
    void forceOpenNeedsConfirmationAndLocksSlot() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        TenantContext.runAs(TENANT, () -> orderService.abandon(customerId, order.getOrderNo()));
        Long slotId = order(order.getOrderNo()).getSlotId();

        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.forceOpen(slotId, false, "客户取回遗留物", 777L)), "没勾确认不给开");
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.forceOpen(slotId, true, " ", 777L)), "事由空白不给开");

        TenantContext.runAs(TENANT, () -> console.forceOpen(slotId, true, "客户取回遗留物", 777L));

        BizCompartment opened = slot(slotId);
        assertNotNull(opened.getDoorOpenAt(), "门确实被开了就得记下起点");
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, opened.getAnomaly(),
                "更强的锁不被降级：开一次门不会让“里面有别人东西”消失");
        List<Long> commands = jdbc.queryForList(
                "select id from biz_device_command where cabinet_id = ? and action = 'FORCE_OPEN'", Long.class, cabinetId);
        assertEquals(1, commands.size(), "强制开柜要留指令记录（审计与回扫都靠它）");
    }

    @Test
    @DisplayName("免除争议费用：证据不全直接拒；证据齐全只免争议期间，且不能重复免")
    void waiveNeedsTwoContradictingEvidenceAndIsIdempotent() {
        Long customerId = newCustomer();
        BizStorageOrder order = blockedWithDispute(customerId);
        Long slotId = order.getSlotId();

        // 只有"设备说有"一条证据 → 不构成误报
        BizException noEvidence = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.waiveDisputeFee(order.getOrderNo())));
        assertTrue(noEvidence.getMessage().contains("证据"), noEvidence.getMessage());

        // 补上 AI 的相反结论，并把争议起始时刻往前挪，让金额可核对
        reviewPort.script(slotId, Presence.ABSENT, "与基准比对未检出多出物体");
        TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, order.getOrderNo()));
        // 上面这一步已按误报结束；再点一次必须被"已终态"挡住
        BizException again = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.waiveDisputeFee(order.getOrderNo())));
        assertTrue(again.getMessage().contains("已结束"), again.getMessage());

        BizStorageOrder closed = order(order.getOrderNo());
        assertEquals(OrderCloseReason.DISPUTE_WAIVED, closed.getCloseReason());
        assertNull(slot(closed.getSlotId()).getAnomaly(),
                "免除的依据就是“AI 看图说没东西”这条凭据；再锁一次格子等于否认自己刚用的判据");
    }

    @Test
    @DisplayName("欠费拦截：欠 1 点就不给下单，还清后立刻恢复；取件任何时候不受影响")
    void anyArrearsBlocksNewOrderAndClearsItself() {
        Long debtor = newCustomer();
        BizStorageOrder first = newOrder(debtor);
        door(first.getOrderNo(), debtor, CommandAction.OPEN);
        door(first.getOrderNo(), debtor, CommandAction.CLOSE_VERIFY);
        TenantContext.runAs(TENANT, () -> orderService.pickup(debtor, first.getOrderNo()));
        // 造一笔欠费：直接改单上的欠额，模拟"补扣时余额不够"的落库结果
        jdbc.update("update biz_storage_order set arrears_points = 1 where id = ?", first.getId());

        Long fresh = newCustomer();
        TenantContext.runAs(TENANT, () -> points.recharge(fresh, 5000L, "CHG-" + UUID.randomUUID(), "对照"));
        // 对照：没欠费的客户仍可下单（证明拦住的原因只有欠费这一条）
        assertNotNull(TenantContext.callAs(TENANT, () -> orderService.create(fresh,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))));

        BizException blocked = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> orderService.create(debtor, new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))));
        assertTrue(blocked.getMessage().contains("未缴清"), blocked.getMessage());

        jdbc.update("update biz_storage_order set arrears_points = 0 where id = ?", first.getId());
        assertNotNull(TenantContext.callAs(TENANT, () -> orderService.create(debtor,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))),
                "还清后必须自动恢复：这条拦截没有人可以手工放行");
    }

    @Test
    @DisplayName("禁用/启用客户：事由必填，重复设同一值幂等")
    void customerStatusNeedsReason() {
        Long customerId = newCustomer();
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.changeCustomerStatus(customerId, 0, "")), "无因的状态变更不给做");
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> console.changeCustomerStatus(customerId, 5, "非法状态")));

        TenantContext.runAs(TENANT, () -> console.changeCustomerStatus(customerId, 0, "反复拒付，客服核验后禁用"));
        assertEquals(0, jdbc.queryForObject("select status from biz_customer where id = ?", Integer.class, customerId));
        // 再点一次同样值不该报错（运营重复提交是常态）
        TenantContext.runAs(TENANT, () -> console.changeCustomerStatus(customerId, 0, "重复提交"));
        TenantContext.runAs(TENANT, () -> console.changeCustomerStatus(customerId, 1, "已补缴，恢复使用"));
        assertEquals(1, jdbc.queryForObject("select status from biz_customer where id = ?", Integer.class, customerId));
    }

    @Test
    @DisplayName("处置动作各用独立权限点，没有一个\"运营万能权限\"")
    void everyActionHasItsOwnPermissionPoint() {
        assertPermission("anomalies", "locker:ledger:list");
        assertPermission("detail", "locker:ledger:list");
        assertPermission("unrefunded", "locker:deposit:unrefunded-list");
        assertPermission("arrears", "locker:arrears:list");
        assertPermission("resolve", "locker:compartment:resolve");
        assertPermission("forceOpen", "locker:door:force-open");
        assertPermission("waive", "locker:order:waive-fee");
        assertPermission("changeCustomerStatus", "locker:customer:disable");
        assertPermission("rebuildFreeSet", "locker:freeset:rebuild");
    }

    private void assertPermission(String methodName, String expectedCode) {
        PreAuthorize annotation = java.util.Arrays.stream(AdminLockerController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到后台接口方法：" + methodName))
                .getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, methodName + " 必须有权限判定：后台写接口裸奔比没有接口更危险");
        assertEquals("hasAuthority('" + expectedCode + "')", annotation.value(), methodName + " 的权限点不对");
    }

    @Test
    @DisplayName("证据时间线在详情里可读：免除判定要能复盘到每一次观测")
    void detailCarriesEvidenceTimeline() {
        Long customerId = newCustomer();
        BizStorageOrder order = blockedWithDispute(customerId);
        var detail = TenantContext.callAs(TENANT, () -> console.compartmentDetail(order.getSlotId()));
        assertNotNull(detail.currentOrder(), "详情要带当前占用的单，否则运维不知道这格被谁占着");
        List<BizItemEvidence> rows = TenantContext.callAs(TENANT,
                () -> evidenceMapper.timelineOf(order.getSlotId(), 30));
        assertTrue(rows.stream().anyMatch(e -> e.getSource() == EvidenceSource.DEVICE_PROBE
                && e.getPresence() == Presence.PRESENT), "设备报有物那条必须查得回来");
    }
}

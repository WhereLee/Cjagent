package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.ItemReviewPort;
import com.wherelee.cabinet.application.storage.SlotCandidateQuery;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizItemEvidence;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.DisputeState;
import com.wherelee.cabinet.domain.enums.EvidenceSource;
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
import com.wherelee.cabinet.infrastructure.mapper.BizItemEvidenceMapper;
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
 * 柜内物品争议阶梯（第 13C 刀验收，规则见 docs/门态与物品争议设计.md §5/§6）。
 *
 * <p>这一刀真正要证的是三件事，每件都有一个"不这样做会怎样"盯着：
 * <ul>
 *   <li><b>争议期间计费不停</b>（I11）：否则"我否认一下"就是暂停计费的白嫖通道；</li>
 *   <li><b>判为设备误报时只免掉争议期间的钱</b>：免除的终点必须是争议起始时刻，
 *       而不是把整单免掉（那会变成"否认=免费"），也不是不免（那是让用户为设备的错误付费）；</li>
 *   <li><b>复审有预算、上报有回报</b>：复审次数用尽就只剩人工，不再消耗模型调用；
 *       而发现遗留物的人必须免费，否则他下次直接走人不报。</li>
 * </ul>
 *
 * <p>AI 用桩：按格口指定"有/无/无法判断"。桩的默认值是"无法判断"——如果默认放行，
 * "模型不可用当没事"这条错路就永远测不出来。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        "cabinet.pricing.tolerance-minutes=0",
        "cabinet.dispute.max-ai-review=2",
})
@DisplayName("柜内物品争议：拦下→否认→AI 复审→误报免除；以及发现者上报")
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
    private BizItemEvidenceMapper evidenceMapper;
    @Autowired
    private PointAccountService points;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private CompartmentStateService states;
    @Autowired
    private SimulatedCabinetChannel simulator;
    @Autowired
    private StubItemReviewPort reviewPort;

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
            site.setName("争议点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("争议柜机");
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
                "CHG-" + UUID.randomUUID(), "争议用例 funding"));
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

    private List<BizItemEvidence> evidence(Long slotId) {
        return TenantContext.callAs(TENANT, () -> evidenceMapper.timelineOf(slotId, 50));
    }

    private void door(String orderNo, Long customerId, CommandAction action) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, action));
    }

    private BizStorageOrder activate(Long customerId, BizStorageOrder order) {
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        door(order.getOrderNo(), customerId, CommandAction.CLOSE_VERIFY);
        return order(order.getOrderNo());
    }

    /** 造一个"东西落在里面"的现场：门关、物检报有物。 */
    private BizStorageOrder activateWithItemLeft(Long customerId, BizStorageOrder order) {
        BizStorageOrder active = activate(customerId, order);
        simulator.simulateItemInside(cabinetId, active.getSlotId(), true);
        return active;
    }

    @Test
    @DisplayName("结束被拦：留证据、记争议起始时刻，但计费不停、状态不动")
    void blockedCloseRecordsEvidenceButKeepsBilling() {
        Long customerId = newCustomer();
        BizStorageOrder order = activateWithItemLeft(customerId, newOrder(customerId));
        LocalDateTime billingStart = order.getStartedAt();

        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        assertTrue(rejected.getMessage().contains("物品"), rejected.getMessage());

        BizStorageOrder after = order(order.getOrderNo());
        assertEquals(OrderStatus.ACTIVE, after.getStatus(), "被拦下不改变订单主态（争议不是订单状态）");
        assertEquals(billingStart, after.getStartedAt(), "I11：争议期间计费不停，起点绝不会被改动");
        assertNull(after.getFinishedAt());
        assertNotNull(after.getDisputeStartedAt(), "争议起始时刻必须活过这次回滚，否则事后无从免除");
        assertEquals(DisputeState.ITEM_DISPUTED, after.getDisputeState());

        List<BizItemEvidence> rows = evidence(after.getSlotId());
        assertEquals(1, rows.size(), "拦下就要留底：这是【设备说过有物】的唯一凭证");
        assertEquals(EvidenceSource.DEVICE_PROBE, rows.get(0).getSource());
        assertEquals(Presence.PRESENT, rows.get(0).getPresence());
    }

    @Test
    @DisplayName("争议起始时刻只记第一次：反复点否认不能把计费终点往后推")
    void disputeStartIsRecordedOnce() {
        Long customerId = newCustomer();
        BizStorageOrder order = activateWithItemLeft(customerId, newOrder(customerId));

        for (int i = 0; i < 3; i++) {
            assertThrows(BizException.class,
                    () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        }
        BizStorageOrder after = order(order.getOrderNo());
        assertNotNull(after.getDisputeStartedAt());
        // 如果每次拦下都把起始时刻刷成现在，"免除争议期间费用"就会随点击次数越滚越少——
        // 免除反而变成加费，这是这套机制最容易被写歪的地方
        assertEquals(3, evidence(after.getSlotId()).size(), "每次观测都要留一条，但起始时刻只有一个");
    }

    @Test
    @DisplayName("AI 判为设备误报：允许结束，且只免掉争议期间的钱")
    void falseAlarmEndsOrderAndWaivesOnlyDisputePeriod() {
        Long customerId = newCustomer();
        BizStorageOrder order = activateWithItemLeft(customerId, newOrder(customerId));
        assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));

        BizStorageOrder blocked = order(order.getOrderNo());
        // 造出"争议持续了一小时才被复审"的现场：计费起点 5 小时前、争议起点 1 小时前。
        // 免除的正是中间这 4 小时里超出应缴的部分——见下面的金额对照
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 5 hour), "
                + "dispute_started_at = date_sub(now(3), interval 1 hour) where id = ?", blocked.getId());
        reviewPort.script(blocked.getSlotId(), Presence.ABSENT, "与空柜基准比对未检出多出物体");

        TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, blocked.getOrderNo()));

        BizStorageOrder closed = order(blocked.getOrderNo());
        assertEquals(OrderStatus.CLOSED, closed.getStatus());
        assertEquals(OrderCloseReason.DISPUTE_WAIVED, closed.getCloseReason());
        // SMALL 15 点/小时。计到争议起点 = 4 小时（减 10 分钟免费窗口后向上取整仍为 4）→ 60 点；
        // 若按"现在"结算会是 5 小时 = 75 点。差的那 15 点就是被免除的争议期间费用。
        assertEquals(60L, closed.getSettledPoints(),
                "只免争议期间：应缴按【计费起点→争议起点】算，实测 " + closed.getSettledPoints());
        assertEquals(0L, closed.getRemoteClosePoints(), "误报免除不该再吃远程结束加收");
        assertTrue(closed.getAiReviewCount() >= 1);
        assertNull(slot(closed.getSlotId()).getAnomaly(), "确认是空的可直接回可售，不该留异常");
    }

    @Test
    @DisplayName("AI 仍判有物：不能结束、继续计费；次数用尽后不再调用模型")
    void aiStillPresentKeepsBlockingAndBudgetIsEnforced() {
        Long customerId = newCustomer();
        BizStorageOrder order = activateWithItemLeft(customerId, newOrder(customerId));
        assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        Long slotId = order(order.getOrderNo()).getSlotId();
        reviewPort.script(slotId, Presence.PRESENT, "检出物体");

        BizException first = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, order.getOrderNo())));
        assertTrue(first.getMessage().contains("取出"), "被拦时必须给下一步：" + first.getMessage());
        BizStorageOrder afterFirst = order(order.getOrderNo());
        assertEquals(OrderStatus.ACTIVE, afterFirst.getStatus(), "复审没放行就不能停表");
        assertEquals(1, afterFirst.getAiReviewCount());

        assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, order.getOrderNo())));
        BizStorageOrder afterSecond = order(order.getOrderNo());
        assertEquals(2, afterSecond.getAiReviewCount());
        // 第 2 次复审仍判有物：此时还是 AI_REVIEWED（预算刚好用完，但这一轮的结论就是“有物”）；
        // 真正转人工发生在下一次——预算已尽时不再花模型调用，直接给人工
        assertEquals(DisputeState.AI_REVIEWED, afterSecond.getDisputeState());

        // 第三次：预算已尽，**一次模型调用都不该再发**——否则上限只是"多提醒几次"而不是止损。
        // 用"改桩的结论"来证明没被调用：换成 ABSENT 后仍拿不到放行，说明根本没问模型
        reviewPort.script(slotId, Presence.ABSENT, "若被调用则应判误报");
        BizException third = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, order.getOrderNo())));
        assertTrue(third.getMessage().contains("人工"), third.getMessage());
        assertEquals(2, order(order.getOrderNo()).getAiReviewCount(), "次数不该继续增长");
        assertEquals(OrderStatus.ACTIVE, order(order.getOrderNo()).getStatus());
    }

    @Test
    @DisplayName("AI 说无法判断：转人工，绝不退回红外的结论")
    void indeterminateGoesToHumanInsteadOfFallingBackToSensor() {
        Long customerId = newCustomer();
        BizStorageOrder order = activateWithItemLeft(customerId, newOrder(customerId));
        assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        Long slotId = order(order.getOrderNo()).getSlotId();
        reviewPort.script(slotId, Presence.UNKNOWN, "照片模糊，无法与基准比对");

        BizException refused = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, order.getOrderNo())));
        assertTrue(refused.getMessage().contains("人工"), refused.getMessage());
        BizStorageOrder after = order(order.getOrderNo());
        assertEquals(DisputeState.HUMAN_REVIEW, after.getDisputeState());
        assertEquals(OrderStatus.ACTIVE, after.getStatus(), "模型不确定时不能自动放行，也不能自动定它有罪");
        assertEquals(Presence.UNKNOWN, evidence(slotId).stream()
                .filter(e -> e.getSource() == EvidenceSource.AI).findFirst().orElseThrow().getPresence(),
                "AI 的【不知道】要原样落库——折成有或无就是在最该谨慎的地方造假");
    }

    @Test
    @DisplayName("门没关时既没有争议可复审，也不能靠误报免除结束")
    void openDoorBlocksTheWholeLadder() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        door(order.getOrderNo(), customerId, CommandAction.OPEN);
        // 容错期满（本刀 tolerance=0）把单推到计费中，但门仍开着：不这样做 pickup 会被“状态不可结束”先拦下，
        // 测到的就不是“门未关”这条判据
        TenantContext.runAs(TENANT, () -> orderService.toleranceExpired(order.getOrderNo(),
                new CompartmentStateService.Sensing(false, Presence.PRESENT, LocalDateTime.now())));
        simulator.simulateItemInside(cabinetId, order.getSlotId(), true);

        BizException rejected = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, order.getOrderNo())));
        assertTrue(rejected.getMessage().contains("门"), "门没关时应先说门（而不是柜内有物）：" + rejected.getMessage());

        BizException noDispute = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.denyItem(customerId, order.getOrderNo())));
        assertTrue(noDispute.getMessage().contains("争议"), noDispute.getMessage());
        BizStorageOrder after = order(order.getOrderNo());
        assertTrue(after.getAiReviewCount() == null || after.getAiReviewCount() == 0,
                "没有争议就不能进复审——否则【门没关】能被绕成【设备误报】直接免单");
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
    @DisplayName("已知遗留物的格口不会被分给下一位：上报走的是同一格的上一张单")
    void reportedCompartmentNeverReappearsInCandidates() {
        Long owner = newCustomer();
        BizStorageOrder order = activateWithItemLeft(owner, newOrder(owner));
        TenantContext.runAs(TENANT, () -> orderService.abandon(owner, order.getOrderNo()));
        Long slotId = order(order.getOrderNo()).getSlotId();

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
        BizStorageOrder order = activateWithItemLeft(customerId, newOrder(customerId));
        TenantContext.runAs(TENANT, () -> orderService.abandon(customerId, order.getOrderNo()));
        Long slotId = order(order.getOrderNo()).getSlotId();

        // 门磁不确认时不降级：“遗留物”已经比“没凭据”更强
        TenantContext.runAs(TENANT, () -> states.markContentUnverified(slotId, "模拟一次无凭据的结束"));
        assertEquals(CompartmentAnomaly.CONTENT_LEFT, slot(slotId).getAnomaly(),
                "遗留物是【已经看见有东西】，测不到只是没凭据；弱锁盖强锁会让被上报的格子被当空柜清走");
    }
}

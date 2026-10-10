package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.OrderFundService;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-10 定稿的三个新行为（定-2 一步存件、定-4 待收敛回收、S-03 退押金抵欠款）。
 *
 * <p>回收器最有意思的一条是"崩在推进之前"：订单状态停在"待开门"，但开门指令其实已经成功。
 * 这时按状态判断会退回格口，而柜子里可能正放着别人的包——所以判据必须是**指令流水**，
 * 不是订单状态（这条踩过一次：用当前状态推物理事实，判断恰好反过来）。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.reconcile-minutes=0",
        "cabinet.pricing.tolerance-minutes=0",
})
@DisplayName("一步存件、待收敛回收（先看事实）、自助退押金抵欠款")
class PlaceAndReconcileTest {

    private static final Long TENANT = 8127L;
    private static final long FREEZE = 215L;

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
    private OrderFundService funds;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private StorageOrderFacade facade;
    @Autowired
    private DelayTaskService tasks;

    private JdbcTemplate jdbc;
    private String cabinetNo;
    private Long cabinetId;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-P-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("P-" + cabinetNo);
            site.setName("存件点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("存件柜机");
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
    }

    @AfterEach
    void cleanup() {
        boolean hasCustomers = !customerIds.isEmpty();
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
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "P-" + cabinetNo);
        if (hasCustomers) {
            String marks = placeholders();
            jdbc.update("delete from biz_pay_txn where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_point_txn where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_customer where id in (" + marks + ")", customers);
        }
        customerIds.clear();
        TenantContext.clear();
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private Long newCustomer(long funding) {
        Long id = 999000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 9000);
        customerIds.add(id);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '存件客户', 1, now(3), now(3), now(3), 0)",
                id, TENANT, "tk-" + id);
        TenantContext.runAs(TENANT, () -> points.recharge(id, funding, "CHG-" + UUID.randomUUID(), "用例 funding"));
        return id;
    }

    private BizStorageOrder order(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private BizCompartment slot(Long slotId) {
        return TenantContext.callAs(TENANT, () -> slotMapper.selectById(slotId));
    }

    private int runDue() {
        return TenantContext.callAs(TENANT, tasks::runDue);
    }

    @Test
    @DisplayName("存件是一次动作：直接返回格口号并已进入开门，不需要再点一次开柜")
    void placeOpensDoorInOneStep() {
        Long customerId = newCustomer(FREEZE);
        StorageOrderView view = TenantContext.callAs(TENANT, () -> facade.place(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60)));

        assertNotNull(view.slotNo(), "必须当场告诉他第几格");
        assertTrue(view.slotNo().startsWith("S"), "小格口编号应以 S 开头，实际：" + view.slotNo());
        // 门开了但还没关门校验：订单停在 OPENING，计费起点要等关门（或容错期满）才落定
        BizStorageOrder stored = order(view.orderNo());
        assertEquals(OrderStatus.OPENING, stored.getStatus(), "一步存件后应已进入“门已开、待关门”状态");
        assertNotNull(stored.getToleranceUntil(), "开门后必须落定容错到期时刻");
        assertEquals(stored.getId(), slot(stored.getSlotId()).getCurrentOrderId(), "格口已经属于这张单，别人抢不走");
    }

    @Test
    @DisplayName("回收器：从没开过门的单当没发生，释放格口并退回冻结")
    void reaperReleasesOrderThatNeverOpened() {
        Long customerId = newCustomer(FREEZE);
        // 只建单不开门（模拟"崩在发指令之前"）
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        Long slotId = order(orderNo).getSlotId();
        assertEquals(OrderStatus.RESERVED, order(orderNo).getStatus());

        runDue();

        BizStorageOrder reaped = order(orderNo);
        assertEquals(OrderStatus.CANCELLED, reaped.getStatus(), "没开过门的滞留单应被回收取消");
        assertEquals(SlotStatus.FREE, slot(slotId).getStatus(), "格口必须回到可售");
        assertEquals(null, slot(slotId).getAnomaly(), "这种情况不需要人工确认：门压根没开");
        assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getFrozenPoints(),
                "预估冻结要全额退回（押金留在账户栅，不属于本单）");
    }

    @Test
    @DisplayName("回收器：门开过就不许退回格口，改成标待确认（宁可少卖不可卖错）")
    void reaperNeverReleasesASlotThatWasOpened() {
        Long customerId = newCustomer(FREEZE);
        String orderNo = TenantContext.callAs(TENANT, () -> facade.place(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        BizStorageOrder opened = order(orderNo);
        Long slotId = opened.getSlotId();
        // 造"崩在推进之前"的现场：指令已经成功，但订单状态还停在待开门
        jdbc.update("update biz_storage_order set status = 'RESERVED', tolerance_until = null where order_no = ?", orderNo);

        runDue();

        BizStorageOrder still = order(orderNo);
        assertEquals(OrderStatus.RESERVED, still.getStatus(), "有成功开门指令时回收器不得把它取消掉");
        assertEquals(CompartmentAnomaly.CONTENT_UNVERIFIED, slot(slotId).getAnomaly(),
                "必须标待确认进台账：门开过，柜里可能已经有东西");
        assertEquals(still.getId(), slot(slotId).getCurrentOrderId(), "格口不能被放回可售池");
    }

    /** 一步存件后推到“已关门、计费中”，供退押金用例造真实结算现场。 */
    private void closeDoor(Long customerId, String orderNo) {
        TenantContext.runAs(TENANT, () -> facade.openDoor(customerId, orderNo,
                com.wherelee.cabinet.domain.enums.CommandAction.CLOSE_VERIFY));
    }

    @Test
    @DisplayName("退押金：先用押金抵欠款、剩下的回到可用；押金不够抵就继续欠着")
    void refundDepositOffsetsArrearsFirst() {
        Long customerId = newCustomer(FREEZE);
        String orderNo = TenantContext.callAs(TENANT, () -> facade.place(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        closeDoor(customerId, orderNo);
        // 用时长制造真实欠费（封顶后应缴远超冻结）
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 100 hour) where order_no = ?", orderNo);
        TenantContext.runAs(TENANT, () -> facade.pickup(customerId, orderNo));
        long owed = order(orderNo).getArrearsPoints();
        assertTrue(owed > 0, "前置：应产生欠费");

        // 账户押金只有 200（测试库配的门槛），欠款比它大 → 抵不完，抵完仍欠
        long beforePoints = TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints();
        long back = TenantContext.callAs(TENANT, () -> funds.refundDeposit(customerId, "REQ-" + UUID.randomUUID()));

        assertEquals(0L, back, "押金全部被欠款吃掉，退回可用的应是 0");
        assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getDepositPoints(),
                "押金栅必须清零");
        assertEquals(beforePoints, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints(),
                "抵不完的情况下可用余额不应凭空增加");
        assertTrue(order(orderNo).getArrearsPoints() > 0, "押金不够抵，剩下的仍是欠款");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)), "抵欠后账仍必须平");

        // 补够钱再退一次：此时已无押金，必须明确拒绝而不是默默返回 0
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> funds.refundDeposit(customerId, "REQ-" + UUID.randomUUID())));
    }

    @Test
    @DisplayName("欠款还上之后同一笔押金可退，退完可用增加、押金栅归零")
    void refundDepositReturnsRemainder() {
        Long customerId = newCustomer(FREEZE + 2000);
        String orderNo = TenantContext.callAs(TENANT, () -> facade.place(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        closeDoor(customerId, orderNo);
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 100 hour) where order_no = ?", orderNo);
        TenantContext.runAs(TENANT, () -> facade.pickup(customerId, orderNo));
        // 先清偿欠款（充值自动抵扣），再退押金：此时没有欠款，押金应整笔回到可用
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000, "CHG-" + UUID.randomUUID(), "补足欠款"));
        assertEquals(0L, order(orderNo).getArrearsPoints(), "前置：欠款应已被清偿");

        long before = TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints();
        long back = TenantContext.callAs(TENANT, () -> funds.refundDeposit(customerId, "REQ-" + UUID.randomUUID()));

        assertEquals(200L, back, "无欠款时押金整笔退回");
        assertEquals(before + 200L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints());
        assertEquals(OrderStatus.CLOSED, order(orderNo).getStatus(), "退押金不得改动已结算的单");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }
}
